package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TravelPromptContractTest {

    @Test
    void requiresInvariantWordingInPrompt() throws Exception {
        String prompt = Files.readString(Path.of(
                "src/main/resources/prompts/skills/travel.txt"),
                StandardCharsets.UTF_8);

        assertTrue(prompt.contains("travel_collect"),
                "prompt 必须要求调用 travel_collect 收集需求");
        assertTrue(prompt.contains("不得"), "prompt 必须含禁止性措辞");
        assertTrue(prompt.contains("待确认"),
                "prompt 必须要求实时信息来源不足时标记待确认");
    }

    @Test
    void mustNotForceLinearStages() throws Exception {
        String prompt = Files.readString(Path.of(
                "src/main/resources/prompts/skills/travel.txt"),
                StandardCharsets.UTF_8);

        assertFalse(prompt.contains("不得跳过"),
                "不得用「不得跳过」强制线性阶段——对话是跳跃的，应靠不变量约束而非阶段顺序");
        assertFalse(prompt.contains("第 1 阶段") && prompt.contains("第 2 阶段"),
                "不得写死阶段编号");
    }
}
