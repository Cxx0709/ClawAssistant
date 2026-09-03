package com.youkeda.exercise.claw.feature.scout;

public record ScoutSubmissionResult(
        Status status,
        String taskId,
        String error
) {
    public enum Status {
        STARTED,
        DUPLICATE,
        UNAVAILABLE,
        FAILED
    }

    public static ScoutSubmissionResult started(String taskId) {
        return new ScoutSubmissionResult(Status.STARTED, taskId, null);
    }

    public static ScoutSubmissionResult duplicate() {
        return new ScoutSubmissionResult(Status.DUPLICATE, null, null);
    }

    public static ScoutSubmissionResult unavailable(String error) {
        return new ScoutSubmissionResult(Status.UNAVAILABLE, null, error);
    }

    public static ScoutSubmissionResult failed(String error) {
        return new ScoutSubmissionResult(Status.FAILED, null, error);
    }
}
