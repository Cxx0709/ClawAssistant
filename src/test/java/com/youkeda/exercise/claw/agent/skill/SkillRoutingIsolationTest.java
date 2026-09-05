package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.SkillSessionUpdater;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillRoutingIsolationTest {
    @Test
    void topicKeywordMustNotBypassSemanticClassification() {
        SkillDefinition weather = mock(SkillDefinition.class);
        when(weather.name()).thenReturn("weather");
        when(weather.triggerPolicyName()).thenReturn("keywordTriggerPolicy");
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(weather));
        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("weather");
        when(store.find("owner")).thenReturn(Optional.of(session));
        TriggerProperties triggers = new TriggerProperties();
        triggers.setTriggers(Map.of("weather", List.of("天气")));
        SkillLlmRouter semantic = mock(SkillLlmRouter.class);
        when(semantic.route(anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(SkillRoutingResult.fallback());
        SkillRouter router = new SkillRouter(registry, store, mock(TriggerPolicyFactory.class), semantic, triggers);

        assertEquals("common", router.route("帮我写一首描写天气的诗", "owner").primarySkill());
        verify(semantic).route(eq("帮我写一首描写天气的诗"), eq("owner"), eq(registry), eq(Optional.of(session)), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"campus", "transport", "travel", "weather", "anime", "image", "growth-goal", "research"})
    void unrelatedTurnUsesCommonWhileTaskRemainsResumable(String name) {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skill.name()).thenReturn(name);
        when(skill.triggerPolicyName()).thenReturn("testPolicy");
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(skill));
        when(registry.find(name)).thenReturn(Optional.of(skill));
        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession saved = SkillSession.create("owner").withActiveSkill(name);
        when(store.find("owner")).thenReturn(Optional.of(saved));
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(anyString(), any())).thenReturn(SkillTriggerMatch.noMatch());
        TriggerPolicyFactory factory = mock(TriggerPolicyFactory.class);
        when(factory.getPolicy("testPolicy")).thenReturn(policy);
        TriggerProperties triggers = mock(TriggerProperties.class);
        when(triggers.getTriggers()).thenReturn(Map.of());
        SkillLlmRouter semantic = mock(SkillLlmRouter.class);
        when(semantic.route(anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(SkillRoutingResult.fallback());
        SkillRouter router = new SkillRouter(registry, store, factory, semantic, triggers);

        for (String message : List.of("我喜欢小狗", "帮我写诗", "你好")) {
            SkillRoutingResult result = router.route(message, "owner");
            assertEquals("common", result.primarySkill());
            SkillSession next = new SkillSessionUpdater(router, store).update("owner", result);
            assertEquals(name, next.activeSkill());
            assertEquals(1, next.inactivityCount());
        }

        SkillSession pending = saved.withPendingAction("COLLECT_INPUT", "location");
        when(store.find("owner")).thenReturn(Optional.of(pending));
        SkillRoutingResult offTopic = router.route("我喜欢小狗", "owner");
        assertEquals("common", offTopic.primarySkill());
        SkillSession retained = new SkillSessionUpdater(router, store).update("owner", offTopic);
        assertEquals(pending.context(), retained.context());
        assertEquals(name, router.route("确认", "owner").primarySkill());
    }

    @Test
    void contextualLocationAnswerContinuesCampusAndReceivesPreviousQuestion() {
        LLMClient client = mock(LLMClient.class);
        when(client.chatWithSystemPrompt(anyString(), eq("明理北104"), anyList()))
                .thenReturn("{\"primaryIntent\":\"campus\",\"confidence\":0.95}");
        SkillLlmRouter router = new SkillLlmRouter(client, new ObjectMapper());
        try {
            SkillSession session = SkillSession.create("owner").withActiveSkill("campus");
            SkillRoutingResult result = router.route("明理北104", "owner", campusRegistry(), Optional.of(session),
                    List.of(new Message("assistant", "请告诉我考试地点"), new Message("user", "明理北104")));
            assertEquals("campus", result.primarySkill());
            assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, result.action());
            verify(client).chatWithSystemPrompt(contains("请告诉我考试地点"), eq("明理北104"), anyList());
        } finally {
            router.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"primaryIntent\":\"campus\",\"confidence\":0.4}",
            "{\"primaryIntent\":\"missing-skill\",\"confidence\":0.99}",
            "{\"primaryIntent\":\"campus\",\"confidence\":1.5}",
            "{\"primaryIntent\":\"common\",\"confidence\":0.99}", "not json"})
    void uncertainOrInvalidSelectionFallsBack(String response) {
        LLMClient client = mock(LLMClient.class);
        when(client.chatWithSystemPrompt(anyString(), anyString(), anyList())).thenReturn(response);
        SkillLlmRouter router = new SkillLlmRouter(client, new ObjectMapper());
        try {
            assertEquals("common", router.route("我喜欢小狗", "owner", campusRegistry()).primarySkill());
        } finally {
            router.shutdown();
        }
    }

    @Test
    void secondarySkillsMustExistAndCannotRepeatPrimary() {
        LLMClient client = mock(LLMClient.class);
        when(client.chatWithSystemPrompt(anyString(), anyString(), anyList()))
                .thenReturn("{\"primaryIntent\":\"campus\",\"confidence\":0.95,\"secondaryIntents\":[\"missing\",\"campus\",\"common\"]}");
        SkillLlmRouter router = new SkillLlmRouter(client, new ObjectMapper());
        try {
            assertEquals(Set.of(), router.route("我的课表", "owner", campusRegistry()).secondarySkills());
        } finally {
            router.shutdown();
        }
    }

    private SkillRegistry campusRegistry() {
        SkillDefinition campus = mock(SkillDefinition.class);
        when(campus.name()).thenReturn("campus");
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(campus));
        return registry;
    }
}
