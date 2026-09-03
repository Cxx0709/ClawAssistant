package com.youkeda.exercise.claw.skill;

import java.time.Duration;
import java.time.Instant;

public record WorkflowRequest(
        String taskId,
        String workflowName,
        String payload,
        Instant createdAt,
        Duration timeout,
        int retryMax
) {
    public WorkflowRequest(String taskId, String workflowName,
                           String payload, Instant createdAt) {
        this(taskId, workflowName, payload, createdAt, null, -1);
    }
}
