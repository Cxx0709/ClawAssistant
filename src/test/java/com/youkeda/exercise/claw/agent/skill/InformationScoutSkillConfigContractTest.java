package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationScoutSkillConfigContractTest {

    @Test
    void declaresBackgroundWorkflowExecutionWithoutLlmTools() throws Exception {
        String config = Files.readString(Path.of(
                "src/main/resources/config/skills.yml"), StandardCharsets.UTF_8);
        String scoutBlock = config.substring(
                config.indexOf("    information-scout:"),
                config.indexOf("  workflows:"));

        assertTrue(scoutBlock.contains("description:"));
        assertTrue(scoutBlock.contains("mode: BACKGROUND_WORKFLOW"));
        assertTrue(scoutBlock.contains("executorName: informationScoutSkillExecutor"));
        assertTrue(scoutBlock.contains("requiredTools: []"));
        assertTrue(scoutBlock.contains("optionalTools: []"));
        assertFalse(scoutBlock.contains("scout_rss"));
        assertFalse(scoutBlock.contains("information_scout"));
    }
}
