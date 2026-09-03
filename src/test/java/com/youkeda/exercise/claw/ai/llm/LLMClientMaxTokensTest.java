package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.infrastructure.common.PromptLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LLMClientMaxTokensTest {

    @Test
    void includesPerRequestMaxTokens() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LLMClient client = new LLMClient(
                new LLMProperties(), objectMapper, mock(PromptLoader.class));

        String body = client.buildRequestBody(
                "system", "prompt", List.of(), 320);

        JsonNode root = objectMapper.readTree(body);
        assertEquals(320, root.path("max_tokens").asInt());
    }
}
