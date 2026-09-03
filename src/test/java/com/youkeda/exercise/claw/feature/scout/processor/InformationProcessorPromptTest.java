package com.youkeda.exercise.claw.feature.scout.processor;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InformationProcessorPromptTest {

    @Test
    void summaryPromptPreservesOriginalDomain() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString()))
                .thenReturn("教育政策变化可能影响相关考生安排。");
        when(embeddingClient.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{1f, 0f}));
        InformationProcessor processor = new InformationProcessor(embeddingClient, llmClient);
        InformationItem item = InformationItem.create(
                "研究生考试政策更新",
                "教育部门发布了新的考试安排。",
                "https://example.org/news",
                "WEB_SEARCH",
                "NEWS");

        processor.process(List.of(item));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains("不要改变信息所属领域"));
        assertTrue(prompt.getValue().contains("类别：NEWS"));
        assertFalse(prompt.getValue().contains("开发者/创业者"));
    }
}
