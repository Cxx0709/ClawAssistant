package com.youkeda.exercise.claw.web.conversation;

public record ToolTraceItem(
        String id,
        String name,
        String skill,
        String state,
        Long durationMs,
        String detail,
        String traceId,
        String confirmPayload,
        String eventType) {

    /** 兼容旧调用：6 参数构造（无 trace 事件语义）。 */
    public ToolTraceItem(String id, String name, String skill, String state, Long durationMs, String detail) {
        this(id, name, skill, state, durationMs, detail, null, null, null);
    }

    /** 普通新增 SSE 事件（APPEND）。 */
    public static ToolTraceItem append(String id, String name, String skill, String state,
                                       Long durationMs, String detail, String traceId, String confirmPayload) {
        return new ToolTraceItem(id, name, skill, state, durationMs, detail, traceId, confirmPayload, "APPEND");
    }

    /** 更新已有 trace 事件（UPDATE），按 traceId 原地替换。 */
    public static ToolTraceItem update(String traceId, String state, Long durationMs, String detail) {
        return new ToolTraceItem(null, null, null, state, durationMs, detail, traceId, null, "UPDATE");
    }
}
