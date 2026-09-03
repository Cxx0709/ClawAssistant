package com.youkeda.exercise.claw.agent.activity;

import java.time.Instant;

public record AgentActivity(
        long id,
        String requestId,
        ActivityEventType eventType,
        String skillName,
        String toolName,
        String status,
        String summary,
        Long durationMs,
        Instant createdAt
) {
}
