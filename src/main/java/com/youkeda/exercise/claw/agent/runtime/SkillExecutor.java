package com.youkeda.exercise.claw.agent.runtime;
import com.youkeda.exercise.claw.skill.SkillExecutionRequest;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;

public interface SkillExecutor {

    String getName();

    SkillExecutionResult execute(SkillExecutionRequest request);
}
