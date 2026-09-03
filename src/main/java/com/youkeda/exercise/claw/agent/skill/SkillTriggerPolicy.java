package com.youkeda.exercise.claw.agent.skill;

import java.util.Optional;

public interface SkillTriggerPolicy {

    SkillTriggerMatch match(String currentMessage, Optional<SkillSession> currentSession);
}
