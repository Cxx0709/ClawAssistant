package com.youkeda.exercise.claw.skill;
import com.youkeda.exercise.claw.agent.skill.SkillSession;

public record SkillExecutionRequest(
        SkillDefinition skill,
        String currentMessage,
        SkillSession session,
        String workflowName
) {
}
