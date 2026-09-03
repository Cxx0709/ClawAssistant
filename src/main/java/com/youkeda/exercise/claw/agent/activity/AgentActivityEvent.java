package com.youkeda.exercise.claw.agent.activity;

public record AgentActivityEvent(
        String requestId,
        ActivityEventType eventType,
        String skillName,
        String toolName,
        String status,
        String summary,
        Long durationMs
) {
}
