package com.youkeda.exercise.claw.feature.scout.task;

import java.time.Instant;

public record ScoutTask(
        String taskId,
        String query,
        ScoutTaskStatus status,
        Instant createdAt,
        Instant completedAt,
        String summary
) {
}
