package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.agent.SkillSessionUpdater;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SkillRouterWeatherSearchTest {
    private final SkillSessionStore store = mock(SkillSessionStore.class);
    private SkillRouter router;

    @BeforeEach
    void setUp() {
        SkillDefinition weather = mock(SkillDefinition.class);
        when(weather.name()).thenReturn("weather");
        when(weather.triggerPolicyName()).thenReturn("keywordTriggerPolicy");
        when(weather.priority()).thenReturn(2);
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(weather));
        when(registry.find("weather")).thenReturn(Optional.of(weather));
        when(store.find("owner")).thenReturn(Optional.of(
                SkillSession.create("owner").withActiveSkill("weather")));
        TriggerProperties triggers = new TriggerProperties();
        triggers.setTriggers(Map.of("weather", List.of("天气", "气温", "下雨")));
        TriggerPolicyFactory policies = mock(TriggerPolicyFactory.class);
        when(policies.getPolicy("keywordTriggerPolicy"))
                .thenReturn(new KeywordTriggerPolicy(triggers));
        router = new SkillRouter(registry, store, policies, mock(SkillLlmRouter.class), triggers);
    }

    @Test
    void nearbyCoffeeSearchClearsPreviousWeatherSession() {
        SkillRoutingResult routing = router.route("搜索附近的咖啡店", "owner");
        assertEquals("common", routing.primarySkill());
        SkillSession updated = new SkillSessionUpdater(router, store).update("owner", routing);
        assertEquals("common", updated.activeSkill());
        verify(store).save("owner", updated);
    }

    @Test
    void weatherSearchStillUsesWeather() {
        SkillRoutingResult routing = router.route("搜索杭州明天的天气", "owner");
        assertEquals("weather", routing.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, routing.action());
    }

    @Test
    void ellipticalWeatherFollowUpKeepsWeather() {
        SkillRoutingResult routing = router.route("明天呢", "owner");
        assertEquals("weather", routing.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, routing.action());
    }

    @Test
    void freshWeatherRequestStillActivatesWeather() {
        when(store.find("owner")).thenReturn(Optional.empty());
        SkillRoutingResult routing = router.route("今天天气怎么样", "owner");
        assertEquals("weather", routing.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.ACTIVATE, routing.action());
    }
}
