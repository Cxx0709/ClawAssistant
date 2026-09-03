package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InformationScoutPromptContractTest {

    @Test
    void requiresNoStatusNarrationInPrompt() throws Exception {
        String prompt = Files.readString(Path.of(
                "src/main/resources/prompts/skills/information-scout.txt"),
                StandardCharsets.UTF_8);

        assertTrue(prompt.contains("受理确认"));
        assertFalse(prompt.contains("告知用户任务状态"));
        assertFalse(prompt.contains("说明这是后台任务"));
        assertFalse(prompt.contains("告诉用户已经开始查找"));
        assertFalse(prompt.contains("调用 information_scout"));
        assertTrue(prompt.contains("SkillExecutor"));
    }
}
