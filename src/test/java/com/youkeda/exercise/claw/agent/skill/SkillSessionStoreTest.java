package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillSessionStoreTest {

    @Test
    void testSkillSession_create() {
        SkillSession session = SkillSession.create("user1");
        assertEquals("user1", session.userId());
        assertEquals("common", session.activeSkill());
        assertNull(session.previousSkill());
        assertEquals(0, session.inactivityCount());
    }

    @Test
    void testSkillSession_switchSkill() {
        SkillSession session = SkillSession.create("user1");
        SkillSession switched = session.withActiveSkill("travel");
        assertEquals("travel", switched.activeSkill());
        assertEquals("common", switched.previousSkill());
        assertEquals(0, switched.inactivityCount());
    }

    @Test
    void testSkillSession_switchMultiple() {
        SkillSession session = SkillSession.create("user1");
        SkillSession switched = session.withActiveSkill("travel");
        SkillSession switchedAgain = switched.withActiveSkill("weather");
        assertEquals("weather", switchedAgain.activeSkill());
        assertEquals("travel", switchedAgain.previousSkill());
    }

    @Test
    void testSkillSession_inactivity() {
        SkillSession session = SkillSession.create("user1");
        SkillSession incremented = session.withIncrementInactivity();
        assertEquals(1, incremented.inactivityCount());
    }

    @Test
    void testSkillSession_resetInactivity() {
        SkillSession session = SkillSession.create("user1");
        SkillSession incremented = session.withIncrementInactivity().withIncrementInactivity();
        assertEquals(2, incremented.inactivityCount());

        SkillSession reset = incremented.withResetInactivity();
        assertEquals(0, reset.inactivityCount());
    }
}
