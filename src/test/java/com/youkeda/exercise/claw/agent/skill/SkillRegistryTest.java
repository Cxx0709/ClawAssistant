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

class SkillRegistryTest {

    @Test
    void shouldUseConfigurationMapKeyAsSkillName() {
        SkillsProperties properties = new SkillsProperties();
        SkillDefinition configuredSkill = new SkillDefinition(
                null,
                "信息猎手",
                3,
                Set.of("信息", "搜索"),
                Set.of(),
                Set.of(),
                "prompts/skills/information-scout.txt",
                "scoutTriggerPolicy",
                null,
                null,
                true
        );
        properties.setSkills(new LinkedHashMap<>(Map.of("information-scout", configuredSkill)));

        SkillRegistry registry = new SkillRegistry(properties, new ToolRegistry());
        registry.init();

        assertEquals("information-scout", registry.find("information-scout").orElseThrow().name());
    }
}
