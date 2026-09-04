package com.youkeda.exercise.claw.ai.llm;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeEmbeddingClientConfigIsolationTest {

    @Test
    void shouldUseDedicatedEmbeddingClientInsteadOfChatLlmConfiguration() {
        com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient delegate =
                mock(com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient.class);
        EmbeddingProperties embeddingProperties = new EmbeddingProperties();
        embeddingProperties.setDimension(3);

        float[] expected = new float[] {1.0f, 2.0f, 3.0f};
        List<float[]> expectedBatch = List.of(expected);
        when(delegate.embed("旅行规划")).thenReturn(expected);
        when(delegate.embedBatch(List.of("旅行规划"))).thenReturn(expectedBatch);

        DashScopeEmbeddingClient client =
                new DashScopeEmbeddingClient(delegate, embeddingProperties);

        assertArrayEquals(expected, client.embed("旅行规划"));
        assertEquals(expectedBatch, client.embedBatch(List.of("旅行规划")));
        assertEquals(3, client.getDimensions());
        verify(delegate).embed("旅行规划");
        verify(delegate).embedBatch(List.of("旅行规划"));
    }
}
