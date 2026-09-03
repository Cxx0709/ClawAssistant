package com.youkeda.exercise.claw.skill;

import java.util.Set;

public record SkillHealth(
        String skillName,
        SkillStatus status,
        Set<String> missingOptionalTools,
        Set<String> missingRequiredTools
) {
    public enum SkillStatus {
        HEALTHY,
        DEGRADED,
        UNAVAILABLE
    }
}
