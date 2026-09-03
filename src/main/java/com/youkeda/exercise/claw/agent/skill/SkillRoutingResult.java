package com.youkeda.exercise.claw.agent.skill;

import jakarta.annotation.Nullable;
import java.util.Set;

public record SkillRoutingResult(
        String primarySkill,
        Set<String> supportingSkills,
        @Nullable SkillRoutingAction action,
        double confidence,
        String reason
) {
    public enum SkillRoutingAction {
        CONTINUE,
        SWITCH,
        ACTIVATE,
        DEACTIVATE,
        NONE
    }

    public static SkillRoutingResult fallback() {
        return new SkillRoutingResult("common", Set.of(), SkillRoutingAction.NONE, 0.0, "fallback to common");
    }
}
