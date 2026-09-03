package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SkillRouterTest {

    @Test
    void testRoutingResult_fallback() {
        SkillRoutingResult result = SkillRoutingResult.fallback();
        assertEquals("common", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.NONE, result.action());
        assertEquals(0.0, result.confidence(), 0.001);
    }

    @Test
    void testRoutingResult_activation() {
        SkillRoutingResult result = SkillRoutingResult.of("travel", Set.of("weather"),
                SkillRoutingResult.SkillRoutingAction.ACTIVATE, 0.9, "test");
        assertEquals("travel", result.primarySkill());
        assertTrue(result.supportingSkills().contains("weather"));
        assertEquals(SkillRoutingResult.SkillRoutingAction.ACTIVATE, result.action());
        assertEquals(0.9, result.confidence(), 0.001);
    }

    @Test
    void testRoutingResult_switch() {
        SkillRoutingResult result = SkillRoutingResult.of("weather", Set.of(),
                SkillRoutingResult.SkillRoutingAction.SWITCH, 0.85, "explicit switch");
        assertEquals("weather", result.primarySkill());
        assertTrue(result.supportingSkills().isEmpty());
        assertEquals(SkillRoutingResult.SkillRoutingAction.SWITCH, result.action());
    }

    @Test
    void testRoutingAction_values() {
        assertEquals(6, SkillRoutingResult.SkillRoutingAction.values().length);
    }
}
