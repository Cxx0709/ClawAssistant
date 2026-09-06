package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.agent.memory.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SkillRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);

    private final SkillRegistry skillRegistry;
    private final SkillSessionStore sessionStore;
    private final TriggerPolicyFactory triggerPolicyFactory;
    private final SkillLlmRouter llmRouter;
    private final TriggerProperties triggerProperties;
    private final RouterConfig config;

    public record RouterConfig(
            int inactiveLimit,
            int sessionTimeoutMinutes,
            boolean llmRouterEnabled
    ) {
        public static RouterConfig defaults() {
            return new RouterConfig(5, 30, true);
        }
    }

    private static final Set<String> NEGATIONS = Set.of("别", "不要", "先不说", "不谈", "不说", "不用", "换", "切到", "切换到");

    /** 续接最低置信度：低于该值视为「与 activeSkill 弱关联」，不再维持旧 skill */
    private static final double CONTINUATION_MIN_CONFIDENCE = 0.7;

    /** 新触发词生效门槛：Layer 3 关键词触发与 pending 抢占共用，置信度达标才算「强新意图」 */
    private static final double NEW_TRIGGER_MIN_CONFIDENCE = 0.8;

    /** 高置信度阈值：用于多技能并行激活判断 */
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.75;

    public SkillRouter(SkillRegistry skillRegistry,
                       SkillSessionStore sessionStore,
                       TriggerPolicyFactory triggerPolicyFactory,
                       SkillLlmRouter llmRouter,
                       TriggerProperties triggerProperties) {
        this.skillRegistry = skillRegistry;
        this.sessionStore = sessionStore;
        this.triggerPolicyFactory = triggerPolicyFactory;
        this.llmRouter = llmRouter;
        this.triggerProperties = triggerProperties;
        this.config = RouterConfig.defaults();
    }

    public SkillRoutingResult route(String message, String userId) {
        return route(message, userId, List.of());
    }

    public SkillRoutingResult route(String message, String userId, List<Message> history) {
        if (message == null || message.isBlank()) return SkillRoutingResult.fallback();

        Optional<SkillSession> sessionOpt = sessionStore.find(userId);

        // Explicit switches must also work while a task is collecting input.
        SkillRoutingResult layer2 = handleExplicitSwitch(message, sessionOpt);
        if (layer2 != null) return layer2;

        // Layer 1: Pending interaction confirmation
        SkillRoutingResult layer1 = handlePendingInteraction(message, userId, sessionOpt, history);
        if (layer1 != null) return layer1;

        // Layer 3: New trigger word match
        SkillRoutingResult layer3 = handleNewTrigger(message, sessionOpt);
        if (layer3 != null && layer3.confidence() >= NEW_TRIGGER_MIN_CONFIDENCE) {
            return layer3;
        }
        if (layer3 != null) return routeSemantically(message, userId, sessionOpt, history);

        // Layer 4: Continuation check
        SkillRoutingResult layer4 = handleContinuation(message, sessionOpt);
        if (layer4 != null) return layer4;

        return routeSemantically(message, userId, sessionOpt, history);
    }

    private SkillRoutingResult routeSemantically(String message, String userId,
            Optional<SkillSession> session, List<Message> history) {
        if (config.llmRouterEnabled()) {
            SkillRoutingResult result = llmRouter.route(message, userId, skillRegistry, session, history);
            if (result != null) return result;
        }
        // NONE retains resumable state; the executor uses primarySkill for this turn.
        return SkillRoutingResult.fallback();
    }

    private SkillRoutingResult handlePendingInteraction(String message, String userId,
            Optional<SkillSession> sessionOpt, List<Message> history) {
        if (sessionOpt.isEmpty()) return null;
        SkillSession session = sessionOpt.get();

        if (session.context().containsKey("pendingAction")) {
            if (isPendingCancellation(message)) {
                return SkillRoutingResult.of("common", Set.of(),
                        SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 1.0,
                        "pending interaction cancelled");
            }

            // 新意图抢占：pending 待确认期间，若消息命中其他 skill 的强触发词
            // （置信度 >= NEW_TRIGGER_MIN_CONFIDENCE，与 Layer 3 同一门槛）且 pending 并非
            // 「正在收集必需输入」（pendingSlot 为空 = 纯确认状态，如行程估价待确认），
            // 则直接切到新 skill，避免 pending 把新意图锁死在 Layer 1。
            // 例：行程估价待确认时「今天天气怎么样」应能切入 weather 技能。
            SkillRoutingResult preempted = tryPreemptPendingWithNewIntent(message, sessionOpt);
            if (preempted != null) {
                log.debug("pending 被强新意图抢占 | from={} | to={} | message={}",
                        session.activeSkill(), preempted.primarySkill(), message);
                return preempted;
            }

            // 待确认状态超时保护：超过会话超时时间后释放 pendingAction，防止 skill 被长期锁定。
            long minutesSinceActivity = ChronoUnit.MINUTES.between(session.lastActivityAt(), Instant.now());
            if (minutesSinceActivity >= config.sessionTimeoutMinutes()) {
                return SkillRoutingResult.of("common", Set.of(),
                        SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 0.0,
                        "pending interaction timeout, release " + session.activeSkill());
            }
            if (message.trim().matches("(?:确认|好的?|可以|行|确认导入|确认保存)[。！!]?")) {
                return SkillRoutingResult.of(session.activeSkill(), Set.of(),
                        SkillRoutingResult.SkillRoutingAction.CONTINUE, 0.95,
                        "pending interaction confirmation for " + session.activeSkill());
            }
            // A pending slot is context, not evidence that every message answers it.
            return routeSemantically(message, userId, sessionOpt, history);
        }
        return null;
    }

    /**
     * 待确认状态下新意图抢占。
     *
     * <p>仅当 pending 处于「纯确认」语义（pendingSlot 为空，如行程估价待确认）时允许抢占：
     * 复用 Layer 3 的触发词匹配（{@link #handleNewTrigger}），命中其他 skill 且置信度达标即返回该结果，
     * 由 {@link SkillSessionUpdater} 以 ACTIVATE 切换 skill（withActiveSkill 会清空旧 context，pending 随之清除）。
     *
     * <p>pending 正在收集必需输入（pendingSlot 非空）时返回 null 不抢占：
     * 此时用户回复很可能就是待收集的输入，触发词会误伤（回答主题「天气」不应路由到 weather 技能）。
     *
     * @return 可抢占的新意图路由结果；不抢占返回 null
     */
    private SkillRoutingResult tryPreemptPendingWithNewIntent(
            String message, Optional<SkillSession> sessionOpt) {
        SkillSession session = sessionOpt.get();

        if ("common".equals(session.activeSkill())) return null;
        String pendingSlot = session.pendingSlot();
        if (pendingSlot != null && !pendingSlot.isBlank()) {
            return null;
        }

        SkillRoutingResult newIntent = handleNewTrigger(message, sessionOpt);
        if (newIntent == null) return null;
        if (newIntent.confidence() < NEW_TRIGGER_MIN_CONFIDENCE) return null;
        if (newIntent.primarySkill().equals(session.activeSkill())) return null;
        return newIntent;
    }

    private boolean isPendingCancellation(String message) {
        if (message == null) return false;
        String normalized = message.replaceAll("\\s+", "");
        return normalized.matches(".*(?:算了|取消|不用了|不查了|别查了|不要查了).*");
    }

    private SkillRoutingResult handleExplicitSwitch(String message, Optional<SkillSession> sessionOpt) {
        boolean hasNegation = NEGATIONS.stream().anyMatch(message::startsWith) || message.startsWith("不说");
        if (!hasNegation) return null;

        String afterNegation = message;
        for (String neg : NEGATIONS) {
            if (message.startsWith(neg)) {
                afterNegation = message.substring(neg.length()).trim();
                break;
            }
        }

        List<SkillDefinition> all = new ArrayList<>(skillRegistry.getAll());
        all.sort(Comparator.comparingInt(SkillDefinition::priority).reversed());

        for (SkillDefinition skill : all) {
            if ("common".equals(skill.name())) continue;

            // 与 Layer 3 相同的「按 skill 取词」：默认关键词策略直接匹配本 skill 的关键词，
            // 避免共享 KeywordTriggerPolicy 把别家 skill 的关键词记到当前遍历的 skill 头上
            // （否则 travel 会抢走 transport 的「怎么去」）。自定义策略（如 transport）走各自 policy。
            boolean isDefaultKeywordPolicy = skill.triggerPolicyName() == null
                    || "keywordTriggerPolicy".equals(skill.triggerPolicyName());

            double confidence;
            boolean matched;
            if (isDefaultKeywordPolicy) {
                List<String> skillKeywords = triggerProperties.getTriggers().get(skill.name());
                if (skillKeywords == null || skillKeywords.isEmpty()) continue;
                matched = skillKeywords.stream().anyMatch(afterNegation::contains);
                confidence = 0.85;
            } else {
                SkillTriggerPolicy policy = triggerPolicyFactory.getPolicy(skill.triggerPolicyName());
                SkillTriggerMatch match = policy.match(afterNegation, Optional.empty());
                matched = match.matched();
                confidence = match.confidence();
            }

            if (matched && confidence >= 0.6) {
                return SkillRoutingResult.of(skill.name(), Set.of(),
                        SkillRoutingResult.SkillRoutingAction.SWITCH,
                        confidence, "explicit switch to " + skill.name());
            }
        }
        return null;
    }

    private SkillRoutingResult handleNewTrigger(String message, Optional<SkillSession> sessionOpt) {
        // Collect keyword triggers once (skill -> matching keywords)
        Map<String, List<String>> triggers = triggerProperties.getTriggers();
        // 防御：无触发词配置（测试 mock 或配置缺失）时按无匹配处理，避免 NPE
        if (triggers == null) return null;

        List<SkillMatchResult> matches = new ArrayList<>();
        List<SkillDefinition> all = new ArrayList<>(skillRegistry.getAll());
        all.sort(Comparator.comparingInt(SkillDefinition::priority).reversed());

        for (SkillDefinition skill : all) {
            if ("common".equals(skill.name())) continue;

            boolean isDefaultKeywordPolicy = skill.triggerPolicyName() == null
                    || "keywordTriggerPolicy".equals(skill.triggerPolicyName());

            if (isDefaultKeywordPolicy) {
                // Direct per-skill keyword matching (fix: shared KeywordTriggerPolicy
                // would attribute matches from other skills' keywords to this skill)
                List<String> skillKeywords = triggers.get(skill.name());
                if (skillKeywords == null || skillKeywords.isEmpty()) continue;

                boolean matched = skillKeywords.stream().anyMatch(message::contains);
                if (matched) {
                    // Topic keywords nominate candidates; they do not establish a task.
                    // 明确的领域关键词应直接激活对应 Skill；否则会被 0.8 的
                    // NEW_TRIGGER_MIN_CONFIDENCE 门槛拦下并交给 LLM 二次分类，
                    // 导致“天气”请求落到 common。
                    matches.add(new SkillMatchResult(skill.name(), 0.85, skill.priority()));
                }
            } else {
                // Custom trigger policy (e.g. TransportTriggerPolicy)
                SkillTriggerPolicy policy = triggerPolicyFactory.getPolicy(skill.triggerPolicyName());
                SkillTriggerMatch match = policy.match(message, sessionOpt);
                if (match.matched()) {
                    matches.add(new SkillMatchResult(skill.name(), match.confidence(), skill.priority()));
                }
            }
        }

        if (matches.isEmpty()) return null;

        if (matches.size() == 1) {
            SkillMatchResult top = matches.get(0);
            SkillRoutingResult.SkillRoutingAction action =
                    sessionOpt.map(s -> s.activeSkill().equals(top.skillName())
                            ? SkillRoutingResult.SkillRoutingAction.CONTINUE
                            : SkillRoutingResult.SkillRoutingAction.ACTIVATE)
                    .orElse(SkillRoutingResult.SkillRoutingAction.ACTIVATE);
            return SkillRoutingResult.of(top.skillName(), Set.of(), action, top.confidence(),
                    "single keyword trigger: " + top.skillName());
        }

        // Multiple matches: resolve by priority
        matches.sort((a, b) -> {
            int cmp = Integer.compare(b.priority(), a.priority());
            if (cmp != 0) return cmp;
            return Double.compare(b.confidence(), a.confidence());
        });

        SkillMatchResult top = matches.get(0);

        // Conflict if same priority
        if (matches.size() > 1 && matches.get(1).priority() == top.priority()) {
            return SkillRoutingResult.of(top.skillName(), Set.of(),
                    SkillRoutingResult.SkillRoutingAction.NONE,
                    0.5, "priority conflict, fall through to LLM");
        }

        // 多技能并行激活：当多个技能置信度都高时，使用 MULTI_ACTIVATE
        if (matches.size() > 1 && matches.get(1).confidence() >= HIGH_CONFIDENCE_THRESHOLD) {
            Set<String> secondarySkills = matches.stream()
                    .skip(1)
                    .filter(m -> m.confidence() >= HIGH_CONFIDENCE_THRESHOLD)
                    .limit(2)  // 最多2个辅助技能
                    .map(SkillMatchResult::skillName)
                    .collect(java.util.stream.Collectors.toSet());

            if (!secondarySkills.isEmpty()) {
                SkillRoutingResult.SkillRoutingAction action =
                        sessionOpt.map(s -> s.activeSkill().equals(top.skillName())
                                ? SkillRoutingResult.SkillRoutingAction.CONTINUE
                                : SkillRoutingResult.SkillRoutingAction.MULTI_ACTIVATE)
                        .orElse(SkillRoutingResult.SkillRoutingAction.MULTI_ACTIVATE);

                return SkillRoutingResult.multi(top.skillName(), secondarySkills, Set.of(),
                        top.confidence(), "multi-skill trigger: " + top.skillName() + " + " + secondarySkills);
            }
        }

        SkillRoutingResult.SkillRoutingAction action =
                sessionOpt.map(s -> s.activeSkill().equals(top.skillName())
                        ? SkillRoutingResult.SkillRoutingAction.CONTINUE
                        : SkillRoutingResult.SkillRoutingAction.ACTIVATE)
                .orElse(SkillRoutingResult.SkillRoutingAction.ACTIVATE);
        return SkillRoutingResult.of(top.skillName(), Set.of(), action, top.confidence(),
                "trigger match (priority resolved): " + top.skillName());
    }

    private SkillRoutingResult handleContinuation(String message, Optional<SkillSession> sessionOpt) {
        if (sessionOpt.isEmpty()) return null;
        SkillSession session = sessionOpt.get();
        if ("common".equals(session.activeSkill())) return null;

        long minutesSinceActivity = ChronoUnit.MINUTES.between(session.lastActivityAt(), Instant.now());
        if (minutesSinceActivity >= config.sessionTimeoutMinutes()) {
            return SkillRoutingResult.of("common", Set.of(),
                    SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 0.0,
                    "session timeout for " + session.activeSkill());
        }

        if (session.inactivityCount() >= config.inactiveLimit()) {
            return SkillRoutingResult.of("common", Set.of(),
                    SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 0.0,
                    "inactivity limit reached for " + session.activeSkill());
        }

        // Default continuation check
        SkillDefinition skillDef = skillRegistry.find(session.activeSkill()).orElse(null);
        if (skillDef == null) return null;

        SkillTriggerMatch match;
        if (skillDef.triggerPolicyName() == null || "keywordTriggerPolicy".equals(skillDef.triggerPolicyName())) {
            // The shared keyword policy scans every skill; continuation must not do that.
            List<String> keywords = triggerProperties.getTriggers() == null ? List.of()
                    : triggerProperties.getTriggers().getOrDefault(skillDef.name(), List.of());
            match = keywords.stream().anyMatch(message::contains)
                    ? new SkillTriggerMatch(true, 0.85, "current skill keyword", true)
                    : SkillTriggerMatch.noMatch();
        } else {
            SkillTriggerPolicy policy = triggerPolicyFactory.getPolicy(skillDef.triggerPolicyName());
            match = policy.match(message, sessionOpt);
        }

        // 高置信度续接：当前消息与 activeSkill 强关联，保持 skill 继续处理
        if (match.matched() && match.confidence() >= CONTINUATION_MIN_CONFIDENCE) {
            return SkillRoutingResult.of(session.activeSkill(), Set.of(),
                    SkillRoutingResult.SkillRoutingAction.CONTINUE, match.confidence(),
                    "continuation of " + session.activeSkill());
        }

        // 天气查询结束后，明确的新搜索请求不应被低置信度续接保护留在天气技能。
        // 不含查询动作的省略追问（如「明天呢」）仍使用原有续接机制。
        if ("weather".equals(session.activeSkill())
                && message.matches("(?s).*(?:搜索|查找|搜一下|搜一搜|帮我找|找一下).+")) {
            return SkillRoutingResult.of("common", Set.of(),
                    SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 1.0,
                    "new search request outside weather");
        }

        if ("campus".equals(session.activeSkill()) && CampusTriggerPolicy.isOutsideScope(message)) {
            return SkillRoutingResult.of("common", Set.of(),
                    SkillRoutingResult.SkillRoutingAction.DEACTIVATE, 1.0,
                    "request outside personal timetable management");
        }

        // No evidence of continuation: classify this turn using bounded context.
        return null;
    }

    private record SkillMatchResult(String skillName, double confidence, int priority) {}
}
