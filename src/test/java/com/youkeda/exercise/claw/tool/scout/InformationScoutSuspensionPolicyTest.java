package com.youkeda.exercise.claw.feature.scout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationScoutSuspensionPolicyTest {

    private final InformationScoutSuspensionPolicy policy =
            new InformationScoutSuspensionPolicy(new ObjectMapper());

    @Test
    void suspendsWhenScoutStarted() {
        LLMResponse.ToolCall call = toolCall("information_scout", "{}");
        assertTrue(policy.shouldSuspend(List.of(call), List.of("{\"status\":\"started\"}")),
                "information_scout 返回 status=started 时应静默");
    }

    @Test
    void doesNotSuspendWhenScoutNotStarted() {
        LLMResponse.ToolCall call = toolCall("information_scout", "{}");
        assertFalse(policy.shouldSuspend(List.of(call), List.of("{\"status\":\"error\"}")),
                "information_scout 未返回 started 时不应静默");
    }

    @Test
    void doesNotSuspendOnNonJsonResult() {
        LLMResponse.ToolCall call = toolCall("information_scout", "{}");
        assertFalse(policy.shouldSuspend(List.of(call), List.of("非 JSON 结果")),
                "非 JSON 结果不能视为已受理后台任务");
    }

    @Test
    void doesNotSuspendForOtherTools() {
        LLMResponse.ToolCall call = toolCall("weather_query", "{}");
        assertFalse(policy.shouldSuspend(List.of(call), List.of("{\"status\":\"started\"}")),
                "非 information_scout 工具不受影响");
    }

    private static LLMResponse.ToolCall toolCall(String name, String args) {
        return new LLMResponse.ToolCall("call-1", name, args);
    }
}
