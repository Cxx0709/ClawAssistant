package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.context.ContextBuilder;
import com.youkeda.exercise.claw.agent.context.ContextUsageTracker;
import com.youkeda.exercise.claw.agent.context.DefaultContextBuilder;
import com.youkeda.exercise.claw.agent.context.HeuristicTokenEstimator;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.ConversationSummaryService;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.memory.TurnInitiator;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.model.*;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ExecutionLoop;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.agent.skill.*;
import com.youkeda.exercise.claw.identity.UserExecutionContext;

/**
 * ReAct 模式 Agent 执行器
 *
 * <p>核心调度器：接收用户消息，通过 LLM + Function Calling 的循环自主决定调用哪些工具，
 * 最终给出回复。支持三路 LLM 输出：文本回复、工具调用、结构化计划。
 *
 * <p>工具白名单（三级模型）：
 * <ol>
 *   <li>global tools — 系统级工具（memory_manage 等），始终可用</li>
 *   <li>common capability tools — 跨 Skill 通用能力（web_search、file_generate 等），
 *       通过 {@link CommonCapabilityRegistry} 管理</li>
 *   <li>active skill tools — 当前活跃 Skill 的 allowedTools()</li>
 * </ol>
 *
 * <p>执行流程：
 * <ol>
 *   <li>取对话历史 + 当前用户消息</li>
 *   <li>加载当前会话的 PlanState（如果有）</li>
 *   <li>快速判断：明显不需要工具的闲聊直接 LLM 回复（不含工具定义），跳过后续循环</li>
 *   <li>调 LLM（带所有已注册的 {@link Tool} 定义）</li>
 *   <li>LLM 返回
 *     <ul>
 *       <li>文本 → 结束，保存回复到上下文</li>
 *       <li>tool_calls → 逐个执行 → 更新 TaskResult → 结果追加到消息列表 → 回到步骤 3</li>
 *       <li>plan → PlanValidator 校验 → PlanStore.save → 回到步骤 3</li>
 *     </ul>
 *   </li>
 *   <li>达到最大轮次 → 返回当前可用结果</li>
 * </ol>
 */
@Component
public class ReActAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentExecutor.class);

    private static final String ERROR_REPLY = "抱歉，AI 服务暂时不可用，请稍后再试。";

    public static final String SILENT_REPLY = "__HANDLED_WITHOUT_USER_REPLY__";

    /** L1 埋点：每 N 次上下文组装打一次汇总日志。 */
    private static final long CONTEXT_USAGE_REPORT_EVERY = 50;

    private final LLMClient llmClient;
    private final ToolRegistry functionRegistry;
    private final ContextStore contextStore;
    private final ObjectMapper objectMapper;
    private final PlanStore planStore;
    private final LongTermMemoryService longTermMemoryService;
    private final ConversationSummaryService conversationSummaryService;
    private final SkillRouter skillRouter;
    private final SkillSessionStore skillSessionStore;
    private final SkillRegistry skillRegistry;
    private final SkillsProperties skillsProperties;
    private final UserExecutionContext userExecutionContext;
    private final SkillKnowledgeService skillKnowledgeService;
    private final AgentActivityRecorder activityRecorder;
    private final SkillExecutionDispatcher skillExecutionDispatcher;
    private final ExecutionLoop executionLoop;
    private final CommonCapabilityRegistry commonCapabilityRegistry;
    private final PendingToolCoordinator pendingToolCoordinator;

    // ==== 批次 2 拆分出的内部 helper（非 Spring bean，构造内用已有依赖创建）====
    private final SystemPromptBuilder systemPromptBuilder;
    /** L1 埋点：上下文占用聚合器（DefaultContextBuilder 与 ConversationSummaryService 共享同一实例）。 */
    private final ContextUsageTracker contextUsageTracker;
    private final SkillSessionUpdater skillSessionUpdater;
    private final ContextBuilder contextBuilder;
    private final SimpleChatClassifier simpleChatClassifier;

    public ReActAgentExecutor(LLMClient llmClient,
                               ToolRegistry functionRegistry,
                               ContextStore contextStore,
                               ObjectMapper objectMapper,
                               PlanStore planStore,
                               LongTermMemoryService longTermMemoryService,
                               ConversationSummaryService conversationSummaryService,
                               SkillRouter skillRouter,
                               SkillSessionStore skillSessionStore,
                               SkillRegistry skillRegistry,
                               SkillsProperties skillsProperties,
                               UserExecutionContext userExecutionContext,
                               SkillKnowledgeService skillKnowledgeService,
                               AgentActivityRecorder activityRecorder,
                               SkillExecutionDispatcher skillExecutionDispatcher,
                               ExecutionLoop executionLoop,
                               CommonCapabilityRegistry commonCapabilityRegistry,
                               PendingToolCoordinator pendingToolCoordinator) {
        this.llmClient = llmClient;
        this.functionRegistry = functionRegistry;
        this.contextStore = contextStore;
        this.objectMapper = objectMapper;
        this.planStore = planStore;
        this.longTermMemoryService = longTermMemoryService;
        this.conversationSummaryService = conversationSummaryService;
        this.skillRouter = skillRouter;
        this.skillSessionStore = skillSessionStore;
        this.skillRegistry = skillRegistry;
        this.skillsProperties = skillsProperties;
        this.userExecutionContext = userExecutionContext;
        this.skillKnowledgeService = skillKnowledgeService;
        this.activityRecorder = activityRecorder;
        this.skillExecutionDispatcher = skillExecutionDispatcher;
        this.executionLoop = executionLoop;
        this.commonCapabilityRegistry = commonCapabilityRegistry;
        this.pendingToolCoordinator = pendingToolCoordinator;

        // 内部 helper 用主类已有的依赖创建，保持 15 参构造签名不变（测试零改动）
        this.contextUsageTracker = new ContextUsageTracker(CONTEXT_USAGE_REPORT_EVERY);
        this.systemPromptBuilder = new SystemPromptBuilder(llmClient, skillKnowledgeService);
        this.skillSessionUpdater = new SkillSessionUpdater(skillRouter, skillSessionStore);
        this.contextBuilder = new DefaultContextBuilder(
                contextStore, longTermMemoryService, conversationSummaryService,
                new HeuristicTokenEstimator(), 0, contextUsageTracker);
        if (conversationSummaryService != null) {
            conversationSummaryService.setUsageTracker(contextUsageTracker);
        }
        this.simpleChatClassifier = new SimpleChatClassifier(llmClient);
    }

    @Override
    public String execute(AgentContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new IllegalArgumentException("AgentContext.userId 不能为空");
        }
        try (UserExecutionContext.Scope ignored = userExecutionContext.open(
                context.getUserId(), context.getConversationId())) {
            return executeBound(context);
        }
    }

    private String executeBound(AgentContext context) {
        long requestStartedAt = System.currentTimeMillis();
        // Web 流式场景：入口已在调用前预生成 requestId 并订阅 recorder → 直接沿用；
        // 未预订阅活动流的入口由 recorder 自行生成 requestId。
        String activityRequestId = context.getActivityRequestId() != null
                ? activityRecorder.beginRequest(context.getActivityRequestId())
                : activityRecorder.beginRequest();
        String userMessage = context.getMessage();
        AgentStreamObserver streamObserver = context.getStreamObserver();

        log.info("AgentExecutor 执行 | message={}", userMessage);

        String userId = context.getUserId();

        // Turn 贯通（ADR Phase 1B）：roundId 由入口（saveMessageToContext 的 beginTurn）生成并随消息传入。
        // 系统触发（定时任务，如 AgentTaskExecutor）无 roundId → 此处自行 beginTurn（initiator=SYSTEM）。
        // 异常逃逸不在此处理：Turn 留 RUNNING，由启动恢复扫描超时转 INCOMPLETE。
        String roundId = context.getRoundId();
        if (roundId == null) {
            roundId = UUID.randomUUID().toString();
            contextStore.beginTurn(roundId, TurnInitiator.SYSTEM, new Message("user", userMessage));
        }

        // Route through SkillRouter
        SkillRoutingResult routingResult = skillRouter.route(userMessage, userId);
        SkillSession session = skillSessionUpdater.update(userId, routingResult);
        context.setSkillSession(session);

        // Get active SkillDefinition
        String activeSkillName = session.activeSkill();
        SkillDefinition activeSkill = skillRegistry.find(activeSkillName).orElse(null);
        activityRecorder.skillSelected(activityRequestId, activeSkillName);

        // Phase 5: Pending Tool Confirmation — 拦截确认/取消消息
        PendingToolCoordinator.Result pendingResult =
                pendingToolCoordinator.handleUserMessage(userId, userMessage);
        if (pendingResult.handled()) {
            String reply = pendingResult.userReply();
            contextStore.appendToTurn(roundId, new Message("assistant", reply));
            contextStore.closeTurn(roundId);
            skillSessionStore.save(userId, session);
            if (pendingResult.type() == PendingToolCoordinator.Result.Type.EXECUTED
                    || pendingResult.type() == PendingToolCoordinator.Result.Type.CANCELLED) {
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
            } else {
                activityRecorder.requestFailed(
                        activityRequestId, reply, System.currentTimeMillis() - requestStartedAt);
            }
            return reply;
        }

        // Skill dispatch (short-circuit)
        SkillExecutionResult skillExecution = skillExecutionDispatcher.dispatch(
                activeSkill, userMessage, session);
        if (skillExecution.status() != SkillExecutionResult.Status.NOT_HANDLED) {
            session = skillExecution.session() == null ? session : skillExecution.session();
            context.setSkillSession(session);
            skillSessionStore.save(userId, session);
            if (skillExecution.status() == SkillExecutionResult.Status.HANDLED_SILENT) {
                contextStore.closeTurn(roundId);
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
                return SILENT_REPLY;
            }
            String reply = skillExecution.message();
            if (reply == null || reply.isBlank()) {
                reply = "当前功能暂时不可用，请稍后重试。";
            }
            contextStore.appendToTurn(roundId, new Message("assistant", reply));
            contextStore.closeTurn(roundId);
            if (skillExecution.status() == SkillExecutionResult.Status.FAILED) {
                activityRecorder.requestFailed(
                        activityRequestId, reply, System.currentTimeMillis() - requestStartedAt);
            } else {
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
            }
            return reply;
        }

        // Build effective tool set (three-tier whitelist)
        // Tier 1: global tools (system-level, always available)
        // Tier 2: common capability tools (cross-skill, e.g. web_search, file_generate)
        // Tier 3: active skill tools (skill-specific allowedTools)
        Set<String> effectiveTools = new LinkedHashSet<>();
        Set<String> globalTools = skillsProperties.getGlobalTools() != null
                ? skillsProperties.getGlobalTools() : Set.of();
        Set<String> commonCapTools = commonCapabilityRegistry.getTools();
        Set<String> skillTools = activeSkill != null
                ? activeSkill.allowedTools() : Set.of();

        effectiveTools.addAll(globalTools);
        effectiveTools.addAll(commonCapTools);
        effectiveTools.addAll(skillTools);

        log.debug("[Tool Assembly] activeSkill: {}", activeSkillName);
        log.debug("[Tool Assembly] globalTools: {}", globalTools);
        log.debug("[Tool Assembly] commonCapabilityTools: {}", commonCapTools);
        log.debug("[Tool Assembly] skillTools: {}", skillTools);
        log.debug("[Tool Assembly] finalTools ({} total): {}", effectiveTools.size(), effectiveTools);

        // Build dynamic system prompt
        String systemPrompt = systemPromptBuilder.build(context, activeSkill);

        // Load PlanState
        PlanState planState = context.getPlanState() != null
                ? context.getPlanState()
                : planStore.get();
        context.setPlanState(planState);

        // History + current message + long-term memory（ContextBuilder 组装，Phase 1C 切换）。
        // Result.messages 为不可变（防御性拷贝），ExecutionLoop 会原地追加，故复制为可变列表。
        List<Message> messages = new ArrayList<>(contextBuilder.build(context).messages());
        boolean continuationRequest = contextBuilder.isContinuationRequest(userMessage);

        // Fast path: simple chat without tools
        if (!continuationRequest && (activeSkill == null || "common".equals(activeSkill.name()))) {
            if (simpleChatClassifier.isSimpleChat(userMessage)) {
                log.debug("快速通道：用户消息不需工具，走纯对话");
                LLMResponse quickResponse = streamObserver != null
                        ? llmClient.chatWithToolsStreaming(
                                systemPrompt, messages, List.of(), streamObserver::onContentDelta)
                        : llmClient.chatWithTools(systemPrompt, messages, List.of());
                if (quickResponse != null && !quickResponse.isToolCall()
                        && quickResponse.getContent() != null
                        && !quickResponse.getContent().isBlank()) {
                    String reply = quickResponse.getContent();
                    log.info("快速对话回复 | reply={}", reply);
                    contextStore.appendToTurn(roundId, new Message("assistant", reply));
                    contextStore.closeTurn(roundId);
                    longTermMemoryService.processAndStoreAsync(userMessage, reply);
                    skillSessionStore.save(userId, session);
                    activityRecorder.requestCompleted(
                            activityRequestId, System.currentTimeMillis() - requestStartedAt);
                    return reply;
                }
                if (llmClient.hasPermanentFailure()) {
                    String failureSummary = llmClient.getLastFailureSummary();
                    log.warn("快速对话遇到不可重试错误，结束请求 | reason={}",
                            failureSummary);
                    activityRecorder.requestFailed(
                            activityRequestId, failureSummary,
                            System.currentTimeMillis() - requestStartedAt);
                    return handleError(roundId);
                }
                log.warn("快速对话路径异常，回退到工具循环");
            }
        }

        // Tool availability filtering
        ToolExecutionContext execContext = new ToolExecutionContext(
                userMessage, session, userId, context.getArtifacts());
        List<ToolDefinition> tools = functionRegistry.getAvailableDefinitions(effectiveTools, execContext);
        log.info("[Agent Available Tools] skill={} | count={} | tools={}",
                activeSkillName, tools.size(),
                tools.stream().map(ToolDefinition::name).toList());

        // Execution loop
        int initialMessageCount = messages.size();
        ExecutionLoop.Result loopResult = executionLoop.run(
                systemPrompt, messages, tools, planState,
                execContext, session, activityRequestId, activeSkillName, userMessage,
                streamObserver != null ? streamObserver::onContentDelta : null);
        session = loopResult.session();
        planState = loopResult.planState();

        // 持久化本轮工具调用与结果到 Turn，使下一轮 LLM 能看到真实的工具执行记录，
        // 避免因历史中缺失工具证据而误判上一轮结果为编造（ADR Phase 1B：写入 appendToTurn）。
        for (int i = initialMessageCount; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m == null) continue;
            boolean isToolResult = m.role() == MessageRole.TOOL;
            boolean isToolCall = m.role() == MessageRole.ASSISTANT && m.isToolCall();
            if (isToolResult || isToolCall) {
                contextStore.appendToTurn(roundId, m);
                log.debug("工具消息已写入 Turn | roundId={} | role={} | toolCallId={}",
                        roundId, m.role(), m.toolCallId());
            }
        }

        // Handle loop result
        return handleLoopResult(loopResult, roundId, userMessage, session, userId,
                systemPrompt, activityRequestId, requestStartedAt, streamObserver);
    }

    // ==================== 循环结果处理 ====================

    private String handleLoopResult(
            ExecutionLoop.Result result,
            String roundId,
            String userMessage,
            SkillSession session,
            String userId,
            String systemPrompt,
            String activityRequestId,
            long requestStartedAt,
            AgentStreamObserver streamObserver) {
        switch (result.status()) {
            case SILENT:
                skillSessionStore.save(userId, session);
                contextStore.closeTurn(roundId);
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
                return SILENT_REPLY;

            case TEXT_REPLY:
                String reply = result.reply();
                contextStore.appendToTurn(roundId, new Message("assistant", reply));
                contextStore.closeTurn(roundId);
                longTermMemoryService.processAndStoreAsync(userMessage, reply);
                session = maybeSetTravelPendingAction(session, reply, userMessage);
                skillSessionStore.save(userId, session);
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
                return reply;

            case LLM_FAILED:
                String failureSummary = llmClient.getLastFailureSummary();
                activityRecorder.requestFailed(
                        activityRequestId, failureSummary,
                        System.currentTimeMillis() - requestStartedAt);
                return handleError(roundId);

            case MAX_ROUNDS:
                String synthesizedReply = streamObserver != null
                        ? executionLoop.synthesize(systemPrompt, result.messages(),
                                streamObserver::onContentDelta)
                        : executionLoop.synthesize(systemPrompt, result.messages());
                contextStore.appendToTurn(roundId, new Message("assistant", synthesizedReply));
                contextStore.closeTurn(roundId);
                longTermMemoryService.processAndStoreAsync(userMessage, synthesizedReply);
                skillSessionStore.save(userId, session);
                activityRecorder.requestCompleted(
                        activityRequestId, System.currentTimeMillis() - requestStartedAt);
                return synthesizedReply;
        }
        throw new IllegalStateException("Unknown loop status: " + result.status());
    }

    // ==================== 错误与兜底 ====================

    /**
     * 旅行技能：当回复包含需要用户决策的内容时，设置 pendingAction，
     * 确保下一轮用户回复能被路由回 travel skill 而非 common。
     *
     * <p>检测场景：
     * <ul>
     *   <li>超预算决策（"超预算"/"超支" + 选项）</li>
     *   <li>方案选择（"方案A"/"方案B"/"你倾向"）</li>
     * </ul>
     */
    private SkillSession maybeSetTravelPendingAction(
            SkillSession session, String reply, String userMessage) {
        if (session == null) return session;
        if (!"travel".equals(session.activeSkill())) return session;
        if (reply == null || reply.isBlank()) return session;

        // 已有 pendingAction 则不覆盖
        if (session.context().containsKey("pendingAction")) return session;

        boolean needsDecision = BUDGET_DECISION.matcher(reply).find()
                || PLAN_SELECTION.matcher(reply).find();
        if (needsDecision) {
            log.info("旅行回复需要用户决策，设置 pendingAction");
            return session.withPendingAction("travel_user_decision", null);
        }
        return session;
    }

    private static final java.util.regex.Pattern BUDGET_DECISION = java.util.regex.Pattern.compile(
            "超预算|超支|接受超支|调整到预算|更新预算|预算上限");

    private static final java.util.regex.Pattern PLAN_SELECTION = java.util.regex.Pattern.compile(
            "方案[A-Z]|你倾向|你选择|请选择|选哪个|选哪种|哪种方案");

    private String handleError(String roundId) {
        contextStore.appendToTurn(roundId, new Message("assistant", ERROR_REPLY));
        contextStore.closeTurn(roundId);
        return ERROR_REPLY;
    }
}
