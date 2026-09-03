package com.youkeda.exercise.claw.agent.activity;

import java.time.Instant;

public record DashboardSummary(
        long requestCount,
        long toolCallCount,
        long failureCount,
        Instant lastActivityAt
) {
}
