package com.youkeda.exercise.claw.agent.activity;

import com.youkeda.exercise.claw.web.conversation.ToolTraceItem;

public record AgentActivityEvent(
        String requestId,
        ActivityEventType eventType,
        String skillName,
        String toolName,
        String status,
        String summary,
        Long durationMs,
        ToolTraceItem toolTrace
) {

    /** 兼容旧调用：7 参数构造（无 toolTrace）。 */
    public AgentActivityEvent(String requestId, ActivityEventType eventType, String skillName,
                              String toolName, String status, String summary, Long durationMs) {
        this(requestId, eventType, skillName, toolName, status, summary, durationMs, null);
    }
}
