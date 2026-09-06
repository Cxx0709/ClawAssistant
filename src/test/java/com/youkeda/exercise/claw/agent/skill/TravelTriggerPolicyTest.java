package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TravelTriggerPolicyTest {

    private final TravelTriggerPolicy policy = new TravelTriggerPolicy();

    @Test
    void matchesExplicitTravelPlanRequest() {
        SkillTriggerMatch m = policy.match("帮我规划三亚三天游",
                Optional.empty());
        assertTrue(m.matched());
        assertTrue(m.confidence() >= 0.8, "触发置信度必须过 SkillRouter 的 0.8 门槛");
    }

    @Test
    void matchesShortTripWithNonChineseDestination() {
        SkillTriggerMatch m = policy.match("去 Bali 玩五天", Optional.empty());
        assertTrue(m.matched(), "非中文目的地也应能触发");
    }

    @Test
    void continuesActiveTravelSession() {
        SkillSession session = SkillSession.create("u").withActiveSkill("travel");
        SkillTriggerMatch m = policy.match("重新规划一下",
                Optional.of(session));
        assertTrue(m.matched());
        assertEquals(0.92, m.confidence(), 0.01);
    }

    @Test
    void continuesActiveTravelWhenUserOnlyAddsPlanFields() {
        SkillSession session = SkillSession.create("u").withActiveSkill("travel");
        SkillTriggerMatch m = policy.match("从淮南出发 2人 下周三出发 总预算1000",
                Optional.of(session));
        assertTrue(m.matched());
        assertTrue(m.continuation());
        assertEquals(0.92, m.confidence(), 0.01);
    }

    @Test
    void continuesActiveTravelWhenUserSelectsAnOption() {
        SkillSession session = SkillSession.create("u").withActiveSkill("travel");
        SkillTriggerMatch m = policy.match("选方案B", Optional.of(session));
        assertTrue(m.matched());
        assertTrue(m.continuation());
    }

    @Test
    void continuesActiveTravelWhenUserSaysBareOptionName() {
        SkillSession session = SkillSession.create("u").withActiveSkill("travel");
        SkillTriggerMatch m = policy.match("方案B", Optional.of(session));
        assertTrue(m.matched());
        assertTrue(m.continuation());
    }

    @Test
    void ignoresKnowledgeQuestion() {
        SkillTriggerMatch m = policy.match("旅游是什么意思", Optional.empty());
        assertFalse(m.matched(), "知识性问题不应触发 travel");
    }

    @Test
    void doesNotMatchGeneralChat() {
        SkillTriggerMatch m = policy.match("你好", Optional.empty());
        assertFalse(m.matched());
    }
}
