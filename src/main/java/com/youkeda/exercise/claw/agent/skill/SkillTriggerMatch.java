package com.youkeda.exercise.claw.agent.skill;

public record SkillTriggerMatch(
        boolean matched,
        double confidence,
        String reason,
        boolean explicit
) {
    public static SkillTriggerMatch noMatch() {
        return new SkillTriggerMatch(false, 0.0, "no match", false);
    }
}
