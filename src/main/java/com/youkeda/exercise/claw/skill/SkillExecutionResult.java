package com.youkeda.exercise.claw.skill;

import com.youkeda.exercise.claw.agent.skill.SkillSession;

public record SkillExecutionResult(
        Status status,
        String message,
        SkillSession session
) {
    public enum Status {
        NOT_HANDLED,
        REPLY,
        HANDLED_SILENT,
        FAILED
    }

    public static SkillExecutionResult notHandled(SkillSession session) {
        return new SkillExecutionResult(Status.NOT_HANDLED, null, session);
    }

    public static SkillExecutionResult reply(String message, SkillSession session) {
        return new SkillExecutionResult(Status.REPLY, message, session);
    }

    public static SkillExecutionResult handledSilent(SkillSession session) {
        return new SkillExecutionResult(Status.HANDLED_SILENT, null, session);
    }

    public static SkillExecutionResult failed(String message, SkillSession session) {
        return new SkillExecutionResult(Status.FAILED, message, session);
    }
}
