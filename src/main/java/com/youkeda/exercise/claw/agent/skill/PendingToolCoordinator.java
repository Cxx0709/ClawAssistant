package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.runtime.Tool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5：待确认工具协调器。
 *
 * <p>管理高风险工具的确认生命周期：
 * <ol>
 *   <li>SafetyPolicy block HIGH 工具 → 创建 PendingToolAction（PENDING_CONFIRMATION）</li>
 *   <li>用户回复"确认"/"好的"/"执行吧" → 直接执行原始 tool call</li>
 *   <li>用户回复"取消"/"不用了" → CANCELLED，不执行</li>
 *   <li>超过 5 分钟未确认 → EXPIRED</li>
 * </ol>
 *
 * <p>确认后直接调用 tool.execute()，不依赖 LLM 重新生成 tool_call。
 */
@Component
public class PendingToolCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PendingToolCoordinator.class);

    /** 确认关键词 */
    private static final Set<String> CONFIRM_KEYWORDS = Set.of(
            "确认", "好的", "行", "可以", "执行吧", "是的", "对", "嗯", "好", "是", "ok", "OK", "Ok", "yes", "Yes", "YES");

    /** 取消关键词 */
    private static final Set<String> CANCEL_KEYWORDS = Set.of(
            "取消", "不用了", "算了", "不执行", "停止", "别", "不要", "不做了", "不删了");

    private final ConcurrentHashMap<String, PendingToolAction> store = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public PendingToolCoordinator(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    // ==================== 创建待确认 ====================

    /**
     * 高风险工具被 SafetyPolicy 拦截后调用，保存原始调用参数（兼容旧调用）。
     */
    public PendingToolAction createPending(String userId, String toolName, String toolArguments) {
        return createPending(userId, toolName, toolArguments, null, null);
    }

    /**
     * 高风险工具被 SafetyPolicy 拦截后调用，保存原始调用参数。
     *
     * @param traceId   关联的工具 trace ID（用于 SSE UPDATE 原地替换）
     * @param requestId 触发该操作的请求 ID（用于推送 SSE 事件）
     */
    public PendingToolAction createPending(String userId, String toolName, String toolArguments,
                                           String traceId, String requestId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        PendingToolAction action = new PendingToolAction(
                id, userId, toolName, toolArguments,
                SafetyPolicy.ToolRiskLevel.HIGH,
                now, now.plusSeconds(PendingToolAction.DEFAULT_TTL_SECONDS),
                PendingToolAction.Status.PENDING_CONFIRMATION,
                traceId, requestId);
        store.put(userId, action);
        log.info("创建待确认操作 | id={} | userId={} | tool={} | traceId={} | args={}",
                id, userId, toolName, traceId, truncate(toolArguments, 80));
        return action;
    }

    // ==================== 用户消息处理 ====================

    /**
     * 处理用户消息：匹配确认/取消/过期语义。
     *
     * @return 处理结果；若用户消息不匹配确认/取消语义，返回 {@link Result#notHandled()}
     */
    public Result handleUserMessage(String userId, String userMessage) {
        if (userId == null || userMessage == null) return Result.notHandled();

        PendingToolAction pending = store.get(userId);
        if (pending == null) return Result.notHandled();
        if (pending.status() != PendingToolAction.Status.PENDING_CONFIRMATION) {
            return Result.notHandled();
        }
        if (pending.isExpired()) {
            return expired(userId, pending);
        }

        String normalized = userMessage.replaceAll("\\s+", "").toLowerCase();

        // 精确匹配确认
        boolean isConfirm = CONFIRM_KEYWORDS.stream().map(String::toLowerCase)
                .anyMatch(k -> normalized.equals(k));
        // 取消匹配
        boolean isCancel = CANCEL_KEYWORDS.stream()
                .anyMatch(normalized::contains);

        if (isCancel) return cancel(userId);
        if (isConfirm) return confirm(userId);

        // 不匹配 → 让 LLM 继续处理
        return Result.notHandled();
    }

    /**
     * 直接确认当前用户的待确认操作（对话关键词确认或前端按钮确认共用）。
     *
     * <p>确认后执行原始 tool call，不依赖 LLM 重新生成。
     *
     * @return 执行结果；无待确认操作或状态不合法时返回 {@link Result#notHandled()}
     */
    public Result confirm(String userId) {
        if (userId == null) return Result.notHandled();
        PendingToolAction pending = store.get(userId);
        if (pending == null) return Result.notHandled();
        if (pending.status() != PendingToolAction.Status.PENDING_CONFIRMATION) {
            return Result.notHandled();
        }
        if (pending.isExpired()) {
            return expired(userId, pending);
        }
        // 执行原始 tool call
        PendingToolAction confirmed = pending.withStatus(PendingToolAction.Status.CONFIRMED);
        store.put(userId, confirmed);
        return executePending(confirmed, userId);
    }

    /**
     * 取消当前用户的待确认操作。
     *
     * @return 取消结果；无待确认操作或状态不合法时返回 {@link Result#notHandled()}
     */
    public Result cancel(String userId) {
        if (userId == null) return Result.notHandled();
        PendingToolAction pending = store.get(userId);
        if (pending == null) return Result.notHandled();
        if (pending.status() != PendingToolAction.Status.PENDING_CONFIRMATION) {
            return Result.notHandled();
        }
        if (pending.isExpired()) {
            return expired(userId, pending);
        }
        store.remove(userId);
        log.info("用户取消待确认操作 | id={} | tool={} | traceId={}", pending.id(), pending.toolName(), pending.traceId());
        return new Result(Result.Type.CANCELLED, pending.withStatus(PendingToolAction.Status.CANCELLED),
                null, "已取消" + displayName(pending.toolName()) + "操作。");
    }

    private Result expired(String userId, PendingToolAction pending) {
        PendingToolAction expired = pending.withExpired();
        store.put(userId, expired);
        log.info("待确认操作已过期 | id={} | tool={} | traceId={}", pending.id(), pending.toolName(), pending.traceId());
        return new Result(Result.Type.EXPIRED, expired, null,
                "操作已超时（超过" + (PendingToolAction.DEFAULT_TTL_SECONDS / 60) + "分钟），请重新发起。");
    }

    // ==================== 执行待确认工具 ====================

    private Result executePending(PendingToolAction action, String userId) {
        Tool tool = toolRegistry.find(action.toolName());
        if (tool == null) {
            store.remove(userId);
            log.error("待确认工具未注册 | id={} | tool={} | traceId={}", action.id(), action.toolName(), action.traceId());
            return new Result(Result.Type.FAILED, action.withStatus(PendingToolAction.Status.EXPIRED),
                    null, "工具 " + action.toolName() + " 暂时不可用。");
        }

        try {
            log.info("执行已确认的高风险工具 | id={} | userId={} | tool={} | traceId={} | args={}",
                    action.id(), userId, action.toolName(), action.traceId(),
                    truncate(action.toolArguments(), 120));

            ToolExecutionContext execCtx = new ToolExecutionContext(
                    "用户已确认执行", SkillSession.create(userId), userId);
            String result = tool.execute(action.toolArguments(), execCtx);

            PendingToolAction executed = action.withStatus(PendingToolAction.Status.EXECUTED);
            store.remove(userId);

            log.info("已确认工具执行完成 | id={} | tool={} | traceId={} | result={}",
                    action.id(), action.toolName(), action.traceId(), truncate(result, 200));

            return new Result(Result.Type.EXECUTED, executed, result,
                    formatConfirmationResult(action.toolName(), result));

        } catch (Exception e) {
            store.remove(userId);
            log.error("已确认工具执行失败 | id={} | tool={} | traceId={} | error={}",
                    action.id(), action.toolName(), action.traceId(), e.getMessage(), e);
            return new Result(Result.Type.FAILED,
                    action.withStatus(PendingToolAction.Status.EXECUTED), null,
                    "执行" + displayName(action.toolName()) + "操作时出错："
                            + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    // ==================== 查询 ====================

    public PendingToolAction findPending(String userId) {
        PendingToolAction action = store.get(userId);
        if (action != null && action.isExpired()) {
            store.put(userId, action.withExpired());
            log.info("查询时发现过期待确认 | id={}", action.id());
            return action.withExpired();
        }
        return action;
    }

    /** For tests */
    void clearForTest(String userId) {
        store.remove(userId);
    }

    // ==================== 结果类型 ====================

    /**
     * 用户消息处理结果。
     */
    public record Result(
            Type type,
            PendingToolAction action,
            String rawToolResult,
            String userReply
    ) {
        public enum Type {
            /** 用户确认后工具已执行 */
            EXECUTED,
            /** 用户取消 */
            CANCELLED,
            /** 已过期 */
            EXPIRED,
            /** 执行失败 */
            FAILED,
            /** 无待确认操作，或用户消息不匹配确认/取消语义 */
            NOT_HANDLED
        }

        public boolean handled() {
            return type != Type.NOT_HANDLED;
        }

        public static Result notHandled() {
            return new Result(Type.NOT_HANDLED, null, null, null);
        }
    }

    // ==================== 工具方法 ====================

    public static String displayName(String toolName) {
        return switch (toolName) {
            case "file_delete" -> "删除文件";
            case "file_update" -> "修改文件";
            case "create_schedule_task" -> "创建提醒";
            case "update_schedule_task" -> "修改提醒";
            case "cancel_schedule_task" -> "取消提醒";
            default -> toolName;
        };
    }

    private String formatConfirmationResult(String toolName, String rawResult) {
        String display = displayName(toolName);
        if (rawResult != null && (
                rawResult.contains("\"status\":\"SUCCESS\"")
                || rawResult.contains("\"status\":\"success\"")
                || rawResult.contains("\"status\":\"created\"")
                || rawResult.contains("\"status\":\"ok\""))) {
            return display + "操作已完成。" + extractSummary(rawResult);
        }
        return display + "操作已执行。";
    }

    private String extractSummary(String raw) {
        try {
            var node = objectMapper.readTree(raw);
            if (node.has("message")) return " " + node.get("message").asText();
            if (node.has("file_id")) return " 文件 ID: " + node.get("file_id").asText();
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "{}";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
