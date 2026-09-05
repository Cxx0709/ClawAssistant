package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.model.ExecutionStatus;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.model.TaskResult;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.web.conversation.ToolTraceItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工具调用执行器。
 *
 * <p>负责完整生命周期：查找工具 → 安全/可用性/去重校验 → 执行 → 记录活动 → 更新计划状态。
 * 不涉及 LLM 通信，纯工具执行层。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** 单次请求允许执行的最大工具数 */
    static final int MAX_TOOL_CALLS = 16;

    private final ToolRegistry toolRegistry;
    private final SafetyPolicy safetyPolicy;
    private final SkillPendingCoordinator skillPendingCoordinator;
    private final PendingToolCoordinator pendingToolCoordinator;
    private final AgentActivityRecorder activityRecorder;
    private final ToolResultStatusParser toolResultStatusParser;
    private final PlanStore planStore;
    private final ObjectMapper objectMapper;
    private final com.youkeda.exercise.claw.skill.SkillRegistry skillRegistry;

    public ToolExecutor(ToolRegistry toolRegistry,
                        SafetyPolicy safetyPolicy,
                        SkillPendingCoordinator skillPendingCoordinator,
                        PendingToolCoordinator pendingToolCoordinator,
                        AgentActivityRecorder activityRecorder,
                        ToolResultStatusParser toolResultStatusParser,
                        PlanStore planStore,
                        ObjectMapper objectMapper,
                        com.youkeda.exercise.claw.skill.SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.safetyPolicy = safetyPolicy;
        this.skillPendingCoordinator = skillPendingCoordinator;
        this.pendingToolCoordinator = pendingToolCoordinator;
        this.activityRecorder = activityRecorder;
        this.toolResultStatusParser = toolResultStatusParser;
        this.planStore = planStore;
        this.objectMapper = objectMapper;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 执行一批工具调用。
     *
     * @param toolCalls         LLM 返回的工具调用
     * @param execContext       执行上下文
     * @param session           当前 Skill 会话（可能被更新）
     * @param planState         当前计划状态（可能被更新）
     * @param activityRequestId 活动记录请求 ID
     * @param activeSkillName   当前技能名
     * @param userMessage       用户原始消息
     * @param executedCalls     已执行调用的签名集合（可变，会新增）
     * @return 执行结果
     */
    public ToolExecutionBatch executeToolCalls(
            List<LLMResponse.ToolCall> toolCalls,
            ToolExecutionContext execContext,
            SkillSession session,
            PlanState planState,
            String activityRequestId,
            String activeSkillName,
            String userMessage,
            Set<String> executedCalls) {

        List<String> results = new ArrayList<>();
        boolean executedInBatch = false;
        int toolCallCount = 0;
        Map<String, ResultStatus> toolStatuses = new LinkedHashMap<>();

        for (LLMResponse.ToolCall tc : toolCalls) {
            String toolName = tc.name();
            String owningSkill = skillRegistry.resolveSkillForTool(toolName);
            String toolSkillName = owningSkill == null ? "common" : owningSkill;
            log.info("工具调用 | name={} | args={} | id={}", toolName, tc.arguments(), tc.id());

            Tool fn = toolRegistry.find(toolName);
            String result;
            ResultStatus resultStatus;
            String callSignature = canonicalCallSignature(toolName, tc.arguments());

            // Phase 1: 安全检查（CanExecute）
            String blockedReason = safetyPolicy.canExecute(toolName, tc.arguments());

            // 工具不存在
            if (fn == null) {
                log.warn("未找到工具: {}", toolName);
                result = "{\"error\":\"未知工具: " + toolName + "\"}";
                resultStatus = ResultStatus.FAILED;
                activityRecorder.toolBlocked(
                        activityRequestId, toolSkillName, toolName, "未知工具");
            }
            // 当前消息不满足工具的严格触发条件
            else if (!fn.isAvailable(execContext)) {
                log.warn("工具调用被可用性策略阻止 | name={} | message={}", toolName, userMessage);
                String reason = fn.getUnavailableReason(execContext);
                result = policyBlocked(reason);
                resultStatus = ResultStatus.BLOCKED;
                activityRecorder.toolBlocked(
                        activityRequestId, toolSkillName, toolName, reason);
            }
            // 安全检查阻止
            else if (blockedReason != null) {
                boolean needsConfirm = blockedReason.contains("CONFIRM_REQUIRED");
                if (needsConfirm) {
                    // ❗ 禁止生成 ERR trace；改为 WAIT_CONFIRM 待确认状态，SSE 原地更新闭环
                    String traceId = UUID.randomUUID().toString();
                    ToolTraceItem waitItem = ToolTraceItem.append(
                            null, toolName, toolSkillName, "WAIT_CONFIRM",
                            0L, "高风险工具，等待人工确认", traceId, tc.arguments());
                    activityRecorder.publishToolTrace(activityRequestId, waitItem);
                    pendingToolCoordinator.createPending(
                            execContext.userId(), toolName, tc.arguments(), traceId, activityRequestId);
                    result = confirmRequiredResult(toolName, traceId);
                } else {
                    result = policyBlocked(blockedReason);
                    activityRecorder.toolBlocked(
                            activityRequestId, toolSkillName, toolName, blockedReason);
                }
                resultStatus = ResultStatus.BLOCKED;
            }
            // 工具调用数量上限
            else if (toolCallCount >= MAX_TOOL_CALLS) {
                result = policyBlocked("本次请求工具调用数量已达上限，请使用已有结果生成答复。");
                resultStatus = ResultStatus.BLOCKED;
                activityRecorder.toolBlocked(
                        activityRequestId, toolSkillName, toolName, "工具调用数量已达上限");
            }
            // 去重（相同工具 + 相同参数）
            else if (!executedCalls.add(callSignature)) {
                result = policyBlocked("相同工具和参数已经执行过，请使用已有结果，不要重复调用。");
                resultStatus = ResultStatus.BLOCKED;
                activityRecorder.toolBlocked(
                        activityRequestId, toolSkillName, toolName, "重复工具调用");
            }
            // 执行
            else {
                toolCallCount++;
                executedInBatch = true;
                long toolStartedAt = System.currentTimeMillis();
                activityRecorder.toolStarted(
                        activityRequestId, toolSkillName, toolName);
                try {
                    result = fn.execute(tc.arguments(), execContext);
                    session = skillPendingCoordinator.afterToolExecution(session, toolName, result);
                    // ToolSkillResolver：工具执行成功后，自动锁定所属 skill
                    String resolvedSkill = skillRegistry.resolveSkillForTool(toolName);
                    if (resolvedSkill != null && !resolvedSkill.equals(session.activeSkill())) {
                        log.info("ToolSkillResolver | tool={} → skill={} (原 skill={})",
                                toolName, resolvedSkill, session.activeSkill());
                        session = session.withActiveSkill(resolvedSkill);
                    }
                    resultStatus = parseResultStatus(result);
                    if (resultStatus == null) {
                        // 防御：解析器返回 null 时按失败处理
                        resultStatus = ResultStatus.FAILED;
                    }
                    // P0-4 fail-closed：UNKNOWN（解析失败）≠ SUCCESS/PARTIAL，活动统计记为失败
                    boolean succeeded = resultStatus == ResultStatus.SUCCESS
                            || resultStatus == ResultStatus.PARTIAL;
                    activityRecorder.toolFinished(
                            activityRequestId, toolSkillName, toolName, succeeded,
                            System.currentTimeMillis() - toolStartedAt,
                            succeeded ? null : resultSummary(result));
                } catch (Exception e) {
                    // 消费化异常：不允许工具异常直接穿透 Agent Loop，
                    // 转换为标准 ERROR ToolResult 使 LLM 下一轮可见并自行恢复。
                    log.error("工具执行异常 | name={} | args={} | error={}",
                            toolName, tc.arguments(), e.getMessage(), e);
                    result = toErrorResult(toolName, e);
                    resultStatus = ResultStatus.FAILED;
                    toolStatuses.put(toolName, resultStatus);
                    activityRecorder.toolFinished(
                            activityRequestId, toolSkillName, toolName, false,
                            System.currentTimeMillis() - toolStartedAt,
                            e.getMessage());
                }
                log.info("工具执行完成 | name={} | result={}", toolName, truncate(result, 200));

                // 更新 PlanState（如果有）
                if (planState != null) {
                    PlanTask matchingTask = findTaskByToolName(planState, toolName);
                    if (matchingTask != null) {
                        matchingTask.setExecutionStatus(ExecutionStatus.DONE);
                        TaskResult taskResult = new TaskResult(
                                matchingTask.getId(), toolName,
                                parseResultStatus(result), result, System.currentTimeMillis());
                        matchingTask.setResult(taskResult);
                        planStore.save(planState);
                    }
                }
            }
            toolStatuses.put(toolName, resultStatus);
            results.add(result);
        }

        return new ToolExecutionBatch(
                results, session, planState, executedInBatch, toolCallCount,
                Map.copyOf(toolStatuses));
    }

    // ==================== 工具方法 ====================

    public record ToolExecutionBatch(
            List<String> results,
            SkillSession session,
            PlanState planState,
            boolean executedInBatch,
            int toolCallCount,
            Map<String, ResultStatus> toolStatuses
    ) {}

    /**
     * 统一 ToolResult 格式：将工具执行异常转换为 LLM 可消费的标准 ERROR JSON。
     *
     * <p>格式：
     * <pre>{@code
     * {
     *   "status": "ERROR",
     *   "errorCode": "TOOL_EXECUTION_FAILED",
     *   "message": "工具执行异常: xxx",
     *   "fallback_required": true
     * }
     * }</pre>
     */
    static String toErrorResult(String toolName, Exception e) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            node.put("status", "ERROR");
            node.put("errorCode", "TOOL_EXECUTION_FAILED");
            node.put("message", "工具 " + toolName + " 执行异常: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            node.put("fallback_required", true);
            return node.toString();
        } catch (Exception jsonEx) {
            return "{\"status\":\"ERROR\",\"errorCode\":\"TOOL_EXECUTION_FAILED\","
                    + "\"message\":\"工具执行异常\",\"fallback_required\":true}";
        }
    }

    /** 高风险工具待确认：给 LLM 的提示明确指向界面上方的确认卡片按钮，并携带 traceId。 */
    private String confirmRequiredResult(String toolName, String traceId) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("status", "BLOCKED");
            node.put("reason", "CONFIRM_REQUIRED");
            node.put("traceId", traceId);
            node.put("confirmHint",
                    "该操作已生成确认卡片。请这样回复用户：此操作需要确认，请点击界面上方黄色确认卡片中的【确认】按钮完成，无需在聊天中输入任何文字。");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"BLOCKED\",\"reason\":\"CONFIRM_REQUIRED\",\"traceId\":\"" + traceId + "\"}";
        }
    }

    private String policyBlocked(String reason) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("status", "BLOCKED");
            node.put("reason", reason);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"BLOCKED\"}";
        }
    }

    private String canonicalCallSignature(String toolName, String arguments) {
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            return toolName + "|" + objectMapper.writeValueAsString(canonicalize(parsed));
        } catch (Exception ignored) {
            return toolName + "|" + arguments;
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node;
    }

    private String resultSummary(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            for (String field : List.of("error", "message", "reason")) {
                String value = node.path(field).asText("");
                if (!value.isBlank()) return value;
            }
            JsonNode warnings = node.path("warnings");
            if (warnings.isArray() && !warnings.isEmpty()) return warnings.get(0).asText();
        } catch (Exception ignored) {
            // Fall back to a compact raw result below.
        }
        return truncate(result, 120);
    }

    /**
     * 根据工具名匹配 PlanState 中「就绪（DAG 依赖已满足）」的任务。
     *
     * <p>批次 2：不再把任意 PENDING 任务标 DONE——只有依赖已满足（{@link PlanState#getReadyTasks()}）
     * 的任务才可执行并推进。优先匹配 description 包含工具名的就绪任务，其次首个就绪任务；
     * 无就绪任务返回 null（不动计划，等待依赖先行）。
     */
    private PlanTask findTaskByToolName(PlanState planState, String toolName) {
        if (planState == null || planState.getTasks() == null) return null;
        List<PlanTask> readyTasks = planState.getReadyTasks();
        if (readyTasks.isEmpty()) return null;
        // 优先匹配 description 包含工具名的就绪任务
        for (PlanTask task : readyTasks) {
            if (task.getDescription() != null
                    && task.getDescription().toLowerCase().contains(toolName.toLowerCase())) {
                return task;
            }
        }
        // 回退：首个就绪任务
        return readyTasks.get(0);
    }

    private ResultStatus parseResultStatus(String resultJson) {
        return toolResultStatusParser.parse(resultJson);
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
