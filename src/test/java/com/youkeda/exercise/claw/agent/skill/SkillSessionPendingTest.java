package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillSessionPendingTest {

    @Test
    void pendingActionSurvivesNormalSessionUpdatesUntilCleared() {
        SkillSession pending = SkillSession.create("owner")
                .withActiveSkill("information-scout")
                .withPendingAction("START_INFORMATION_SCOUT", "query");

        SkillSession updated = pending.withResetInactivity();

        assertTrue(updated.hasPendingAction("START_INFORMATION_SCOUT"));
        assertEquals("query", updated.pendingSlot());
        assertFalse(updated.clearPendingAction().hasPendingAction("START_INFORMATION_SCOUT"));
    }
}
