package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeywordTriggerPolicyTest {

    @Test
    void testMatchTravel() {
        TriggerProperties props = new TriggerProperties();
        Map<String, List<String>> triggers = new HashMap<>();
        triggers.put("travel", List.of("旅游", "旅行", "出游"));
        triggers.put("weather", List.of("天气", "气温"));
        props.setTriggers(triggers);

        KeywordTriggerPolicy policy = new KeywordTriggerPolicy(props);

        SkillTriggerMatch match = policy.match("我想去云南旅游", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8);

        match = policy.match("今天天气怎么样", Optional.empty());
        assertTrue(match.matched());
    }

    @Test
    void testNoMatch() {
        TriggerProperties props = new TriggerProperties();
        props.setTriggers(new HashMap<>());
        KeywordTriggerPolicy policy = new KeywordTriggerPolicy(props);

        SkillTriggerMatch match = policy.match("你好", Optional.empty());
        assertFalse(match.matched());
    }

    @Test
    void testNullMessage() {
        TriggerProperties props = new TriggerProperties();
        props.setTriggers(new HashMap<>());
        KeywordTriggerPolicy policy = new KeywordTriggerPolicy(props);

        SkillTriggerMatch match = policy.match(null, Optional.empty());
        assertFalse(match.matched());
    }

    @Test
    void testEmptyMessage() {
        TriggerProperties props = new TriggerProperties();
        props.setTriggers(new HashMap<>());
        KeywordTriggerPolicy policy = new KeywordTriggerPolicy(props);

        SkillTriggerMatch match = policy.match("", Optional.empty());
        assertFalse(match.matched());
    }
}
