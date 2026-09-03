package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.ConversationSummary;
import com.youkeda.exercise.claw.agent.memory.ConversationSummaryService;
import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 默认上下文组装器（ADR §4，Phase 1C 实现）。
 *
 * <p>替代 {@code MessageHistoryBuilder} 的组装职责：从多源动态组装 LLM 上下文——
 * 最近对话 Turns（{@code getTurns}）+ 当前用户消息 + 长期记忆注入；
 * 输出 {@link ContextBuilder.Result} + 溯源元数据。
 *
 * <p><b>行为与 {@code MessageHistoryBuilder.buildMessages} 保持等价</b>
 * （历史过滤旧上限提示 + 当前消息去重 + 长期记忆 system 注入），
 * 使 {@code ReActAgentExecutor} 切换后 LLM 请求零变化（迁移可回滚）。
 *
 * <p>Phase 1D：预算裁剪在<b>轮次边界</b>，由 {@link ContextBudgetManager} 执行，
 * 最新 Turn 必含。默认 unbounded（不裁剪，渐进迁移保持行为等价），
 * 测试/配置用显式预算构造验证裁剪。
 *
 * <p>Skill 知识由 {@code SystemPromptBuilder} 注入 system prompt，本 Builder 不重复召回。
 *
 * <p>非 Spring bean，由 {@code ReActAgentExecutor} 构造时内部创建
 * （与 {@code MessageHistoryBuilder} 同模式，保持 executor 构造签名不变）。
 */
public class DefaultContextBuilder implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextBuilder.class);

    private static final int DEFAULT_MAX_TURNS = 10;

    private final ContextStore contextStore;
    private final LongTermMemoryService longTermMemoryService;
    private final ConversationSummaryService summaryService;
    private final TokenEstimator tokenEstimator;
    private final ContextBudgetManager budgetManager;
    /** token 预算；≤0 视为 unbounded（不裁剪）。 */
    private final int maxContextTokens;
    /** L1 埋点聚合器（可 null = 不记录）。 */
    private final ContextUsageTracker usageTracker;

    /** 便捷构造：默认启发式估算 + 不裁剪 + 无摘要（行为与 1C 等价，测试用）。 */
    public DefaultContextBuilder(ContextStore contextStore,
                                 LongTermMemoryService longTermMemoryService) {
        this(contextStore, longTermMemoryService, null, new HeuristicTokenEstimator(), 0);
    }

    /**
     * 显式估算器 + 预算构造（供预算裁剪场景与测试）。
     *
     * @param maxContextTokens token 预算；≤0 视为 unbounded 不裁剪
     */
    public DefaultContextBuilder(ContextStore contextStore,
                                 LongTermMemoryService longTermMemoryService,
                                 TokenEstimator tokenEstimator,
                                 int maxContextTokens) {
        this(contextStore, longTermMemoryService, null, tokenEstimator, maxContextTokens);
    }

    /**
     * 完整构造（Phase 3：支持对话摘要）。
     *
     * @param summaryService 对话摘要服务（可 null = 关闭摘要，测试/未启用时）
     */
    public DefaultContextBuilder(ContextStore contextStore,
                                 LongTermMemoryService longTermMemoryService,
                                 ConversationSummaryService summaryService,
                                 TokenEstimator tokenEstimator,
                                 int maxContextTokens) {
        this(contextStore, longTermMemoryService, summaryService, tokenEstimator, maxContextTokens, null);
    }

    /**
     * 完整构造（Phase 3：对话摘要 + L1 埋点）。
     *
     * @param summaryService 对话摘要服务（可 null = 关闭摘要，测试/未启用时）
     * @param usageTracker   上下文占用聚合器（可 null = 不埋点）
     */
    public DefaultContextBuilder(ContextStore contextStore,
                                 LongTermMemoryService longTermMemoryService,
                                 ConversationSummaryService summaryService,
                                 TokenEstimator tokenEstimator,
                                 int maxContextTokens,
                                 ContextUsageTracker usageTracker) {
        this.contextStore = contextStore;
        this.longTermMemoryService = longTermMemoryService;
        this.summaryService = summaryService;
        this.tokenEstimator = tokenEstimator;
        this.maxContextTokens = maxContextTokens;
        this.usageTracker = usageTracker;
        this.budgetManager = new ContextBudgetManager(tokenEstimator);
    }

    @Override
    public Result build(AgentContext context) {
        String userMessage = context.getMessage();
        List<Message> messages = new ArrayList<>();

        // 1. 历史（最近 Turn，最新在前）→ 预算裁剪（轮次边界 + 最新必含）→ flatten 成时间正序
        List<ConversationTurn> turns = loadTurns();
        List<ConversationTurn> trimmed = budgetManager.trimToBudget(turns, maxContextTokens);
        List<Message> history = flatten(trimmed);
        boolean continuationRequest = isContinuationRequest(userMessage);
        for (Message message : history) {
            if (continuationRequest && isLegacyLimitReply(message)) continue;
            messages.add(message);
        }

        // 2. 当前用户消息（去重：历史末条已是本消息时不再追加）
        if (!historyContainsCurrentMessage(history, userMessage)) {
            messages.add(new Message("user", userMessage));
        }

        // 3. 对话摘要（Phase 3）：早期对话的摘要注入最前（优先级高于 LongTermMemory）
        ConversationSummary summary = loadSummary();
        long coveredUntilSeq = 0;
        if (summary != null && summary.coveredUntilSeq() > 0) {
            String summaryText = "【之前的对话摘要】（覆盖到第 " + summary.coveredUntilSeq() + " 轮）：\n"
                    + summary.text();
            messages.add(0, new Message("system", summaryText));
            coveredUntilSeq = summary.coveredUntilSeq();
        }

        // 4. Long-term memory recall（量小、价值高）
        ContextSourceRef memoryRef = null;
        try {
            List<MemoryItem> recalledMemories = longTermMemoryService.recall(userMessage);
            if (!recalledMemories.isEmpty()) {
                String memoryPrompt = longTermMemoryService.buildMemoryPrompt(recalledMemories);
                messages.add(0, new Message("system", memoryPrompt));
                memoryRef = new ContextSourceRef(
                        ContextSource.LONG_TERM_MEMORY, tokenEstimator.estimate(memoryPrompt));
                log.debug("长期记忆已注入 | count={}", recalledMemories.size());
            }
        } catch (Exception e) {
            log.warn("长期记忆召回失败，跳过注入 | error={}", e.getMessage());
        }

        // 5. 异步触发归档判定（窗口外未覆盖 Turn 达阈值才生成摘要，不阻塞本次 build）
        if (summaryService != null) {
            summaryService.asyncArchiveIfNeeded();
        }

        // 溯源元数据：turnId = 保留的最新 Turn roundId（无 Turn 时为 null）
        String turnId = trimmed.isEmpty() ? null : trimmed.get(0).roundId();
        List<ContextSourceRef> sources = new ArrayList<>();
        if (coveredUntilSeq > 0) {
            sources.add(new ContextSourceRef(ContextSource.SUMMARY, tokenEstimator.estimate(
                    summary == null ? "" : summary.text())));
        }
        if (memoryRef != null) sources.add(memoryRef);
        sources.add(new ContextSourceRef(ContextSource.RECENT_TURNS, estimateMessagesTokens(messages)));

        ContextMetadata metadata = new ContextMetadata(turnId, sources);
        int usedTokens = estimateMessagesTokens(messages);
        // L1 埋点：实际发送 token（观察平均值是否受控，而非随轮次线性增长）
        if (usageTracker != null) {
            usageTracker.recordBuild(usedTokens);
        }
        log.debug("ContextBuilder 组装完成 | messages={} | turns={}/{} | tokens={}/{} | coveredUntilSeq={}",
                messages.size(), trimmed.size(), turns.size(), usedTokens, maxContextTokens, coveredUntilSeq);

        return new Result(messages, metadata, List.of(), context.getPlanState(),
                maxContextTokens > 0 ? new ContextBudget(maxContextTokens, usedTokens)
                        : ContextBudget.unbounded(), (int) coveredUntilSeq);
    }

    /** 读当前对话摘要（无 summaryService 或读取失败返回 null）。 */
    private ConversationSummary loadSummary() {
        if (summaryService == null) return null;
        try {
            return summaryService.getSummary();
        } catch (Exception e) {
            log.warn("对话摘要读取失败 | error={}", e.getMessage());
            return null;
        }
    }

    // ==================== 历史加载 ====================

    /** 从存储读最近 Turn（最新在前）。无 Turn 能力时退化为空列表。 */
    private List<ConversationTurn> loadTurns() {
        try {
            return contextStore.getTurns(DEFAULT_MAX_TURNS);
        } catch (Exception e) {
            log.warn("Turn 历史加载失败，退化为空历史 | error={}", e.getMessage());
            return List.of();
        }
    }

    /** 把最新在前的 Turn 列表 flatten 成时间正序的消息列表。 */
    private List<Message> flatten(List<ConversationTurn> turns) {
        List<Message> messages = new ArrayList<>();
        for (int i = turns.size() - 1; i >= 0; i--) {
            messages.addAll(turns.get(i).messages());
        }
        return messages;
    }

    // ==================== 行为对齐 MessageHistoryBuilder ====================

    private boolean historyContainsCurrentMessage(List<Message> history, String userMessage) {
        if (history.isEmpty() || userMessage == null) return false;
        Message last = history.get(history.size() - 1);
        if (last.role() != MessageRole.USER || last.content() == null) return false;
        return last.content().equals(userMessage) || last.content().equals("[语音]" + userMessage);
    }

    /** 判断是否为「继续生成」类延续请求（与 MessageHistoryBuilder 一致） */
    public boolean isContinuationRequest(String userMessage) {
        if (userMessage == null) return false;
        String normalized = userMessage.replaceAll("[\\s，。！!？?]", "");
        return Set.of("继续生成", "继续", "接着生成", "继续完成方案").contains(normalized);
    }

    private boolean isLegacyLimitReply(Message message) {
        if (message == null || message.role() != MessageRole.ASSISTANT || message.content() == null) {
            return false;
        }
        return message.content().contains("本轮处理步骤已达到上限")
                || message.content().contains("请回复\"继续生成\"")
                || message.content().contains("请回复“继续生成”");
    }

    // ==================== 内部辅助 ====================

    /** 估算消息列表总 token（Phase 1D 用 TokenEstimator）。 */
    private int estimateMessagesTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += tokenEstimator.estimate(m);
        }
        return total;
    }
}
