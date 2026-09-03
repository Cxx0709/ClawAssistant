package com.youkeda.exercise.claw.feature.file;

import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileGenerationServiceMarkdownTest {

    @Test
    void generatesUtf8MarkdownFileWithoutRendering() {
        LLMClient llmClient = mock(LLMClient.class);
        ContextStore contextStore = mock(ContextStore.class);
        when(contextStore.getHistory(20)).thenReturn(List.of());

        String markdown = "# Spring Boot 统一异常处理\n\n## 背景\n\n正文内容。\n";
        when(llmClient.chatWithSystemPrompt(anyString(), eq("生成一份 Markdown 入门文档"), anyList()))
                .thenReturn(markdown);

        FileGenerationService service = new FileGenerationService(llmClient, contextStore);
        FileGenerationService.FileGenerationResult result =
                service.generate("生成一份 Markdown 入门文档", "md");

        assertEquals("Spring Boot 统一异常处理.md", result.fileName());
        assertArrayEquals(markdown.getBytes(StandardCharsets.UTF_8), result.fileBytes());
        assertTrue(result.description().contains("Markdown"));
    }
}
