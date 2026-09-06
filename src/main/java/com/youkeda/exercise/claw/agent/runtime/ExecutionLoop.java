package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.model.EvaluationState;
import com.youkeda.exercise.claw.agent.model.ExecutionStatus;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.agent.plan.ValidationResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.PlanDecision;
import com.youkeda.exercise.claw.ai.llm.TaskDefinition;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LLM ↔ Tool 执行循环。
 *
 * <p>职责：接收 system prompt、消息列表、工具定义，驱动 LLM 自主决策调用工具或直接回复。
 * 内部管理：
 * <ul>
 *   <li>LLM 调用（含空响应降级重试）</li>
 *   <li>结构化计划处理（校验、持久化、上下文注入）</li>
 *   <li>文本回复 → 即时返回</li>
 *   <li>工具调用 → 委托 {@link ToolExecutor} → 结果追加到消息列表 → 继续循环</li>
 * </ul>
 *
 * <p>核心原则：不感知 Skill、Memory、Plan 的上层语义——这些由 {@code AgentRuntime} 编排。
 */
@Component
public class ExecutionLoop {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLoop.class);

    /** 工具调用循环最大轮次（可配置，默认 15；手动构造（测试）时用字段初始值） */
    @Value("${agent.max-rounds:15}")
    private int maxRounds = 15;

    /** 单次请求累计工具调用次数上限（独立于 maxRounds，可配置，默认 32） */
    @Value("${agent.max-total-tool-calls:32}")
    private int maxTotalToolCalls = 32;

    private final LLMClient llmClient;
    private final ToolExecutor toolExecutor;
    private final PlanStore planStore;
    private final PlanValidator planValidator;
    private final ObjectMapper objectMapper;
    /** 工具批次执行后的静默策略（业务方注入） */
    private final List<LoopSuspensionPolicy> suspensionPolicies;
    /** Skill 文本回复结束条件校验注册表（业务方注入，如定时提醒防幻觉） */
    private final SkillReplyGuardRegistry replyGuardRegistry;

    public ExecutionLoop(LLMClient llmClient,
                         ToolExecutor toolExecutor,
                         PlanStore planStore,
                         PlanValidator planValidator,
                         ObjectMapper objectMapper,
                         List<LoopSuspensionPolicy> suspensionPolicies,
                         SkillReplyGuardRegistry replyGuardRegistry) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.planStore = planStore;
        this.planValidator = planValidator;
        this.objectMapper = objectMapper;
        this.suspensionPolicies = suspensionPolicies != null ? suspensionPolicies : List.of();
        this.replyGuardRegistry = replyGuardRegistry != null
                ? replyGuardRegistry : new SkillReplyGuardRegistry(List.of());
    }

    /**
     * 运行 LLM ↔ Tool 执行循环（无内容流回调；等价于以 null 回调调用重载，行为不变）。
     */
    public Result run(
            String systemPrompt,
            List<Message> messages,
            List<ToolDefinition> tools,
            PlanState initialPlanState,
            ToolExecutionContext execContext,
            SkillSession session,
            String activityRequestId,
            String activeSkillName,
            String userMessage) {
        return run(systemPrompt, messages, tools, initialPlanState, execContext, session,
                activityRequestId, activeSkillName, userMessage, null);
    }

    /**
     * 运行 LLM ↔ Tool 执行循环。
     *
     * @param systemPrompt     系统提示
     * @param messages         消息列表（可变，调用方传入后会被追加 assistant/tool 消息）
     * @param tools            可用工具定义
     * @param initialPlanState 初始计划状态
     * @param execContext      工具执行上下文
     * @param session          当前 Skill 会话
     * @param activityRequestId 活动记录请求 ID
     * @param activeSkillName  当前技能名
     * @param userMessage      用户原始消息
     * @param contentSink      用户可见正文增量回调（Web 流式打字机用）；为 null 时走全量 LLM 调用
     * @return 执行结果
     */
    public Result run(
            String systemPrompt,
            List<Message> messages,
            List<ToolDefinition> tools,
            PlanState initialPlanState,
            ToolExecutionContext execContext,
            SkillSession session,
            String activityRequestId,
            String activeSkillName,
            String userMessage,
            Consumer<String> contentSink) {

        Set<String> executedCalls = new HashSet<>();
        Set<String> failedTools = new HashSet<>();
        Map<String, ResultStatus> toolStatuses = new HashMap<>();
        boolean forceTextResponse = false;
        int blankTextResponses = 0;
        PlanState planState = initialPlanState;
        int totalToolCalls = 0;
        boolean weatherFallbackEligible = false;

        for (int round = 0; round < maxRounds; round++) {
            log.info("工具调用循环第 {} 轮 | messages={}", round + 1, messages.size());

            // 累计工具调用次数超限 → 强制文本汇总
            if (totalToolCalls >= maxTotalToolCalls) {
                log.warn("累计工具调用次数已达上限 {} | totalToolCalls={}",
                        maxTotalToolCalls, totalToolCalls);
                forceTextResponse = true;
            }

            List<ToolDefinition> roundTools = forceTextResponse
                    ? List.of()
                    : filterFallbackTools(tools, activeSkillName, weatherFallbackEligible);
            // Buffer each round locally. A round may return both visible text and tool calls;
            // that text is provisional and must not reach the client before the round is accepted.
            StringBuilder roundContent = contentSink == null ? null : new StringBuilder();
            LLMResponse response = contentSink != null
                    ? llmClient.chatWithToolsStreaming(systemPrompt, messages, roundTools,
                            roundContent::append)
                    : llmClient.chatWithTools(systemPrompt, messages, roundTools);
            forceTextResponse = false;

            // 工具调用返回 null 时的单轮降级
            if (response == null) {
                String structuredFallback = structuredToolFallback(messages);
                if (structuredFallback != null) {
                    log.warn("LLM 调用失败，使用最近一次工具的结构化追问结果完成回复");
                    return Result.textReply(
                            structuredFallback, messages, planState, session);
                }
                if (llmClient.hasPermanentFailure()) {
                    log.warn("LLM 调用为不可重试错误，结束循环 | reason={}",
                            llmClient.getLastFailureSummary());
                    return Result.llmFailed(messages, planState, session);
                }
                if (!roundTools.isEmpty()) {
                    log.warn("本轮带工具的 LLM 调用失败，降级不带工具重试（下一轮恢复工具）");
                    roundContent = contentSink == null ? null : new StringBuilder();
                    response = contentSink != null
                            ? llmClient.chatWithToolsStreaming(systemPrompt, messages, List.of(),
                                    roundContent::append)
                            : llmClient.chatWithTools(messages, List.of());
                }
                if (response == null) {
                    log.warn("LLM 调用失败，结束循环");
                    return Result.llmFailed(messages, planState, session);
                }
            }

            // === 分支 1：结构化计划 ===
            if (response.isPlan()) {
                PlanDecision plan = response.getPlan();
                log.info("LLM 返回计划 | goal={} | tasks={}",
                        plan.getGoal(),
                        plan.getTasks().stream().map(TaskDefinition::getId).toList());

                logAgentLoop(round + 1, activeSkillName, tools.size(),
                        "plan", List.of(), List.of(),
                        totalToolCalls, maxTotalToolCalls);

                PlanState newPlan = planDecisionToState(plan);
                ValidationResult vr = planValidator.validate(newPlan);
                if (!vr.valid()) {
                    log.warn("计划校验失败 | errors={}", vr.errors());
                    String errorMsg = "你生成的计划存在结构问题："
                            + String.join("；", vr.errors())
                            + "。请修正后重新生成。";
                    messages.add(new Message("system", errorMsg));
                    continue;
                }
                if (!vr.warnings().isEmpty()) {
                    log.info("计划警告 | warnings={}", vr.warnings());
                }

                newPlan.setVersion(planState != null ? planState.getVersion() + 1 : 1);
                planStore.save(newPlan);
                planState = newPlan;

                // 所有任务都已完成 → 结束循环
                if (newPlan.getTasks().stream()
                        .allMatch(t -> t.getExecutionStatus() == ExecutionStatus.DONE
                                || t.getEvaluationState() == EvaluationState.SUPERSEDED)) {
                    log.info("所有计划任务已完成，进入最终回复");
                    forceTextResponse = true;
                    continue;
                }
                // 有任务未完成，继续让 LLM 执行
                injectPlanContext(messages, newPlan);
                continue;
            }

            // === 分支 2：直接回复文本 ===
            if (!response.isToolCall()) {
                String reply = response.getContent();

                if (reply == null || reply.isBlank()) {
                    String structuredFallback = structuredToolFallback(messages);
                    if (structuredFallback != null) {
                        log.warn("LLM 返回空正文，使用最近一次工具的结构化追问结果完成回复"
                                + " | finishReason={}", response.getFinishReason());
                        return Result.textReply(
                                structuredFallback, messages, planState, session);
                    }
                    if (blankTextResponses++ == 0) {
                        log.warn("LLM 返回空正文，强制无工具重试一次 | finishReason={}",
                                response.getFinishReason());
                        messages.add(new Message("system",
                                "上一次响应正文为空。请直接给用户一个完整、非空的文本回复，"
                                        + "不要调用工具，也不要只输出思考过程。"));
                        forceTextResponse = true;
                        continue;
                    }
                    log.warn("LLM 连续返回空正文，结束循环 | finishReason={}",
                            response.getFinishReason());
                    return Result.llmFailed(messages, planState, session);
                }

                // Skill 回复守卫（注册表，业务方注入）：命中则注入 correction 提示重试
                SkillReplyGuard.GuardResult guardResult = replyGuardRegistry.validate(
                        activeSkillName, userMessage, reply, session, executedCalls, toolStatuses);
                if (!guardResult.allowed()) {
                    log.warn("Skill 回复守卫阻止文本结束 | skill={} | correction={}",
                            activeSkillName, guardResult.correction());
                    messages.add(new Message("system",
                            Objects.requireNonNull(guardResult.correction(),
                                    "guard correction must not be null")));
                    continue;
                }

                log.info("LLM 直接回复 | reply={}", reply);

                if (contentSink != null) contentSink.accept(reply);

                logAgentLoop(round + 1, activeSkillName, tools.size(),
                        "text", List.of(), List.of(),
                        totalToolCalls, maxTotalToolCalls);

                return Result.textReply(reply, messages, planState, session);
            }

            // === 分支 3：工具调用 ===
            List<LLMResponse.ToolCall> toolCalls = response.getToolCalls();
            ToolExecutor.ToolExecutionBatch batch = toolExecutor.executeToolCalls(
                    toolCalls, execContext, session, planState,
                    activityRequestId, activeSkillName, userMessage, executedCalls, failedTools);
            session = batch.session();
            planState = batch.planState();
            totalToolCalls += batch.toolCallCount();

            // Phase 3: Agent Loop 汇总日志
            logAgentLoop(round + 1, activeSkillName, tools.size(),
                    "tool_calls", toolCalls, batch.results(),
                    totalToolCalls, maxTotalToolCalls);

            // 静默策略（批次 2 外移，业务方注入）：命中表示已受理后台任务，
            // 必须在追加 assistant/tool 消息之前返回，否则本轮工具消息会进入上下文
            for (LoopSuspensionPolicy policy : suspensionPolicies) {
                if (policy.shouldSuspend(toolCalls, batch.results())) {
                    log.info("静默策略命中 | policy={}，本轮保持静默",
                            policy.getClass().getSimpleName());
                    return Result.silent(messages, batch.planState(), batch.session());
                }
            }

            // 添加 assistant 消息（合并本轮所有 tool_calls）
            addAssistantMessage(messages, toolCalls, response.getReasoningContent());

            // 添加 tool 结果消息
            for (int i = 0; i < toolCalls.size(); i++) {
                messages.add(new Message("tool", batch.results().get(i),
                        null, null, null, toolCalls.get(i).id(), null));
            }

            // 本轮没有任何工具实际执行 → 下一轮强制文本回复
            if (!batch.executedInBatch()) {
                forceTextResponse = true;
            }

            // 记录本轮工具执行状态（供分支 2 文本结束守卫校验交付不变量）
            toolStatuses.putAll(batch.toolStatuses());
            batch.toolStatuses().forEach((toolName, status) -> {
                if (status == ResultStatus.FAILED) failedTools.add(toolName);
            });
            if ("weather".equals(activeSkillName)
                    && toolCalls.stream().anyMatch(call -> "weather_query".equals(call.name()))) {
                weatherFallbackEligible = batch.results().stream()
                        .anyMatch(this::requiresWebSearchFallback);
            }
        }

        // 达到局部上限
        log.warn("工具调用循环达到上限 {} 轮", maxRounds);
        return Result.maxRounds(messages, planState, session);
    }

    private List<ToolDefinition> filterFallbackTools(List<ToolDefinition> tools,
                                                       String activeSkillName,
                                                       boolean weatherFallbackEligible) {
        if (!"weather".equals(activeSkillName) || weatherFallbackEligible) return tools;
        return tools.stream().filter(tool -> !"web_search".equals(tool.name())).toList();
    }

    private boolean requiresWebSearchFallback(String result) {
        if (result == null || result.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.path("fallback_required").asBoolean(false)
                    || "UNAVAILABLE".equalsIgnoreCase(node.path("status").asText())
                    || "ERROR".equalsIgnoreCase(node.path("status").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 当工具已经给出可直接展示的补充问题、但后续 LLM 为空或失败时，生成确定性降级回复。
     * 只检查当前用户消息之后的工具结果，避免误用历史轮次中的旧问题。
     */
    private String structuredToolFallback(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == MessageRole.USER) {
                break;
            }
            if (message.role() != MessageRole.TOOL
                    || message.content() == null || message.content().isBlank()) {
                continue;
            }
            try {
                JsonNode result = objectMapper.readTree(message.content());
                if (!"NEED_MORE_INFORMATION".equalsIgnoreCase(
                        result.path("status").asText())) {
                    continue;
                }
                JsonNode questions = result.path("questions");
                if (!questions.isArray() || questions.isEmpty()) {
                    continue;
                }
                StringBuilder reply = new StringBuilder("为了继续规划，我还需要确认：");
                int count = Math.min(2, questions.size());
                for (int q = 0; q < count; q++) {
                    String question = questions.get(q).asText("").trim();
                    if (!question.isEmpty()) {
                        reply.append('\n').append(q + 1).append(". ").append(question);
                    }
                }
                return reply.indexOf("\n") >= 0 ? reply.toString() : null;
            } catch (Exception e) {
                log.debug("忽略无法解析的结构化工具降级结果 | error={}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 达到循环上限后，让 LLM 基于已有结果生成最终回复（无内容流回调）。
     */
    public String synthesize(String systemPrompt, List<Message> messages) {
        return synthesize(systemPrompt, messages, null);
    }

    /**
     * 达到循环上限后，让 LLM 基于已有结果生成最终回复。
     *
     * @param systemPrompt 系统提示
     * @param messages     当前消息列表（含工具执行结果）
     * @param contentSink  用户可见正文增量回调；为 null 时走全量调用
     * @return LLM 合成的回复文本
     */
    public String synthesize(String systemPrompt, List<Message> messages,
                             Consumer<String> contentSink) {
        List<Message> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new Message("system",
                "工具调用轮次已结束。请仅根据已有结果回复："
                        + "若信息不足，提出一个明确问题让用户补充；"
                        + "若信息已齐全，给出当前结果；缺失信息标记待确认，不得编造。"));
        LLMResponse response = contentSink != null
                ? llmClient.chatWithToolsStreaming(systemPrompt, finalMessages, List.of(), ignored -> { })
                : llmClient.chatWithTools(systemPrompt, finalMessages, List.of());
        if (response != null && response.getContent() != null
                && !response.getContent().isBlank()) {
            if (contentSink != null) contentSink.accept(response.getContent());
            return response.getContent();
        }
        log.warn("最终汇总仍返回工具调用，使用兜底消息");
        return "已根据当前可用信息整理方案。尚未核实的信息标记为待确认。";
    }

    // ==================== 循环结果 ====================

    public enum LoopStatus {
        /** LLM 直接返回了文本回复 */
        TEXT_REPLY,
        /** LLM 返回 null，已降级重试仍失败 */
        LLM_FAILED,
        /** 后台任务已受理，需静默返回 */
        SILENT,
        /** 达到循环上限，需要使用 synthesize 汇总 */
        MAX_ROUNDS
    }

    public record Result(
            LoopStatus status,
            String reply,
            List<Message> messages,
            PlanState planState,
            SkillSession session
    ) {
        static Result textReply(String reply, List<Message> messages,
                                PlanState planState, SkillSession session) {
            return new Result(LoopStatus.TEXT_REPLY, reply, messages, planState, session);
        }

        static Result llmFailed(List<Message> messages,
                                PlanState planState, SkillSession session) {
            return new Result(LoopStatus.LLM_FAILED, null, messages, planState, session);
        }

        static Result silent(List<Message> messages,
                             PlanState planState, SkillSession session) {
            return new Result(LoopStatus.SILENT, null, messages, planState, session);
        }

        static Result maxRounds(List<Message> messages,
                                PlanState planState, SkillSession session) {
            return new Result(LoopStatus.MAX_ROUNDS, null, messages, planState, session);
        }
    }

    // ==================== Plan 辅助方法 ====================

    private PlanState planDecisionToState(PlanDecision decision) {
        List<PlanTask> tasks = new ArrayList<>();
        if (decision.getTasks() != null) {
            for (TaskDefinition td : decision.getTasks()) {
                tasks.add(new PlanTask(td.getId(), td.getDescription(), td.getDependencies()));
            }
        }
        return new PlanState(decision.getGoal(), tasks);
    }

    private void injectPlanContext(List<Message> messages, PlanState plan) {
        StringBuilder planSummary = new StringBuilder();
        planSummary.append("【当前计划】\n目标：").append(plan.getGoal()).append("\n\n任务进度：\n");
        for (PlanTask task : plan.getTasks()) {
            planSummary.append("  - ").append(task.getId()).append(": ").append(task.getDescription());
            planSummary.append(" [").append(task.getExecutionStatus()).append("]");
            if (task.getResult() != null && task.getResult().getSummary() != null) {
                planSummary.append(" → ").append(task.getResult().getSummary());
            }
            planSummary.append("\n");
        }
        messages.add(new Message("system", planSummary.toString().strip()));
    }

    // ==================== 消息辅助方法 ====================

    private void addAssistantMessage(
            List<Message> messages,
            List<LLMResponse.ToolCall> toolCalls,
            String reasoningContent) {
        if (toolCalls.size() == 1) {
            LLMResponse.ToolCall tc = toolCalls.get(0);
            messages.add(new Message(MessageRole.ASSISTANT, tc.arguments(),
                    null, null, null, tc.id(), tc.name(), reasoningContent));
        } else {
            StringBuilder ids = new StringBuilder();
            StringBuilder names = new StringBuilder();
            ArrayNode argsArray = objectMapper.createArrayNode();
            for (LLMResponse.ToolCall tc : toolCalls) {
                if (ids.length() > 0) ids.append(",");
                ids.append(tc.id());
                if (names.length() > 0) names.append(",");
                names.append(tc.name());
                try {
                    argsArray.add(objectMapper.readTree(tc.arguments()));
                } catch (Exception e) {
                    argsArray.add(tc.arguments());
                }
            }
            String combinedArgs;
            try {
                combinedArgs = objectMapper.writeValueAsString(argsArray);
            } catch (Exception e) {
                combinedArgs = "[]";
                log.warn("多 tool_call 参数序列化失败", e);
            }
            messages.add(new Message(MessageRole.ASSISTANT, combinedArgs,
                    null, null, null, ids.toString(), names.toString(), reasoningContent));
            log.info("合并 {} 个并行工具调用 | ids={} | names={}",
                    toolCalls.size(), ids, names);
        }
    }

    // ==================== Agent Loop 日志 ====================

    /**
     * Phase 3：Agent Loop 级别汇总日志。
     *
     * <p>每轮打印一行，用于排查 Function Calling 问题。
     * userMessage 截断 60 字符，tool args 不打印（脱敏）。
     */
    private void logAgentLoop(int iteration, String activeSkill, int availableTools,
                              String responseType, List<LLMResponse.ToolCall> toolCalls,
                              List<String> toolResults, int currentTotalToolCalls,
                              int maxTotalToolCallsParam) {
        if (!log.isInfoEnabled()) return;

        List<String> toolNames = toolCalls.stream()
                .map(LLMResponse.ToolCall::name)
                .toList();
        List<String> resultSummaries = toolResults.stream()
                .map(r -> {
                    if (r == null) return "null";
                    // 只取 status 字段，不打印完整结果（避免日志膨胀）
                    if (r.contains("\"status\":\"SUCCESS\"")) return "SUCCESS";
                    if (r.contains("\"status\":\"BLOCKED\"")) return "BLOCKED";
                    if (r.contains("\"status\":\"ERROR\"")) return "ERROR";
                    if (r.contains("\"status\":\"PARTIAL\"")) return "PARTIAL";
                    return r.length() > 20 ? r.substring(0, 20) + "..." : r;
                })
                .toList();

        log.info("[Agent Loop] iteration={} | activeSkill={} | availableTools={} | "
                        + "responseType={} | toolCalls={} | toolResults={} | totalToolCalls={}/{}",
                iteration, activeSkill, availableTools,
                responseType, toolNames, resultSummaries,
                currentTotalToolCalls, maxTotalToolCallsParam);
    }
}
