package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillSessionPendingTest {

    @Test
    void pendingActionSurvivesNormalSessionUpdatesUntilCleared() {
        SkillSession pending = SkillSession.create("owner")
                .withActiveSkill("research")
                .withPendingAction("START_RESEARCH", "query");

        SkillSession updated = pending.withResetInactivity();

        assertTrue(updated.hasPendingAction("START_RESEARCH"));
        assertEquals("query", updated.pendingSlot());
        assertFalse(updated.clearPendingAction().hasPendingAction("START_RESEARCH"));
    }
}
