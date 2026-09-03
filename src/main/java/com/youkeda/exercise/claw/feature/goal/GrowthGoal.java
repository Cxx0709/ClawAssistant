package com.youkeda.exercise.claw.feature.goal;

import java.time.Instant;

/** 用户可长期推进的结构化成长目标。 */
public record GrowthGoal(
        long id,
        String userId,
        String title,
        String successCriteria,
        String deadline,
        Status status,
        int progress,
        String latestEvidence,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        ACTIVE,
        COMPLETED,
        CANCELLED
    }
}
