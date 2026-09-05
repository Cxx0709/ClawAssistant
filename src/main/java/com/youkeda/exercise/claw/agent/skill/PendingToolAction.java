package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.agent.SafetyPolicy;

import java.time.Instant;

/**
 * Phase 5：待确认工具操作。
 *
 * <p>高风险工具被 SafetyPolicy 拦截后，保存原始 tool call 参数。
 * 用户确认后直接恢复执行，不依赖 LLM 重新生成 tool_call。
 *
 * @param id            唯一 ID
 * @param userId        用户标识
 * @param toolName      工具名
 * @param toolArguments 原始 JSON 参数（不可变）
 * @param riskLevel     风险等级
 * @param createdAt     创建时间
 * @param expireAt      过期时间（默认 5 分钟）
 * @param status        状态
 * @param traceId       关联的工具 trace ID（用于 SSE UPDATE 原地替换）
 * @param requestId     触发该操作的请求 ID（用于推送 SSE 事件）
 */
public record PendingToolAction(
        String id,
        String userId,
        String toolName,
        String toolArguments,
        SafetyPolicy.ToolRiskLevel riskLevel,
        Instant createdAt,
        Instant expireAt,
        Status status,
        String traceId,
        String requestId
) {
    public enum Status {
        /** 等待用户确认 */
        PENDING_CONFIRMATION,
        /** 已确认，等待执行 */
        CONFIRMED,
        /** 已执行完成 */
        EXECUTED,
        /** 已取消 */
        CANCELLED,
        /** 已过期 */
        EXPIRED
    }

    /** 默认过期时间：5 分钟 */
    public static final long DEFAULT_TTL_SECONDS = 300;

    /** 兼容旧调用：8 参数构造（无 traceId / requestId）。 */
    public PendingToolAction(String id, String userId, String toolName, String toolArguments,
                             SafetyPolicy.ToolRiskLevel riskLevel, Instant createdAt,
                             Instant expireAt, Status status) {
        this(id, userId, toolName, toolArguments, riskLevel, createdAt, expireAt, status, null, null);
    }

    public PendingToolAction withStatus(Status newStatus) {
        return new PendingToolAction(
                id, userId, toolName, toolArguments, riskLevel,
                createdAt, expireAt, newStatus, traceId, requestId);
    }

    public PendingToolAction withExpired() {
        return new PendingToolAction(
                id, userId, toolName, toolArguments, riskLevel,
                createdAt, expireAt, Status.EXPIRED, traceId, requestId);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expireAt);
    }
}
