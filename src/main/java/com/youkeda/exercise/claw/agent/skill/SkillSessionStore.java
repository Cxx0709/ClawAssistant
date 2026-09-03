package com.youkeda.exercise.claw.agent.skill;

import java.util.Optional;

public interface SkillSessionStore {

    Optional<SkillSession> find(String userId);

    void save(String userId, SkillSession session);

    void delete(String userId);
}
