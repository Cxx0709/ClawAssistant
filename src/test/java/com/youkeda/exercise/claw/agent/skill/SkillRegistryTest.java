package com.youkeda.exercise.claw.agent.skill;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;

import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SkillRegistryTest {
    @Test
    void requiredToolOwnerWinsOverOptionalConsumerRegardlessOfRegistrationOrder() {
        SkillDefinition travel = new SkillDefinition("travel", "travel", 5, Set.of(), Set.of(),
                Set.of("weather_query"), null, null, null, null, true);
        SkillDefinition weather = new SkillDefinition("weather", "weather", 2, Set.of(), Set.of("weather_query"),
                Set.of(), null, null, null, null, true);
        SkillsProperties properties = new SkillsProperties();
        Map<String, SkillDefinition> definitions = new LinkedHashMap<>();
        definitions.put("travel", travel);
        definitions.put("weather", weather);
        properties.setSkills(definitions);
        ToolRegistry tools = mock(ToolRegistry.class);
        com.youkeda.exercise.claw.ai.llm.ToolDefinition definition = mock(com.youkeda.exercise.claw.ai.llm.ToolDefinition.class);
        when(definition.name()).thenReturn("weather_query");
        when(tools.getAllDefinitions()).thenReturn(java.util.List.of(definition));
        SkillRegistry registry = new SkillRegistry(properties, tools);
        registry.init();
        assertEquals("weather", registry.resolveSkillForTool("weather_query"));
    }

    @Test
    void shouldUseConfigurationMapKeyAsSkillName() {
        SkillsProperties properties = new SkillsProperties();
        SkillDefinition configuredSkill = new SkillDefinition(
                null,
                "信息研究",
                3,
                Set.of("信息", "搜索"),
                Set.of(),
                Set.of(),
                "prompts/skills/research.txt",
                "researchTriggerPolicy",
                null,
                null,
                true
        );
        properties.setSkills(new LinkedHashMap<>(Map.of("research", configuredSkill)));

        SkillRegistry registry = new SkillRegistry(properties, new ToolRegistry());
        registry.init();

        assertEquals("research", registry.find("research").orElseThrow().name());
    }
}
