package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationPropertiesExampleImportTest {

    @Test
    void importsSkillAndTriggerYamlForCleanDeployments() throws Exception {
        String properties = Files.readString(
                Path.of("src/main/resources/application.properties.example"),
                StandardCharsets.UTF_8);

        assertTrue(properties.contains(
                "spring.config.import=classpath:config/skills.yml,classpath:config/skill-triggers.yml"));
    }
}