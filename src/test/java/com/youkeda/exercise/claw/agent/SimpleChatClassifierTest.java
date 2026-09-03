package com.youkeda.exercise.claw.agent;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleChatClassifierTest {

    @Test
    void emotionalConversationUsesDeterministicFastPath() {
        LLMClient llmClient = mock(LLMClient.class);
        SimpleChatClassifier classifier = new SimpleChatClassifier(llmClient);

        assertTrue(classifier.isSimpleChat("我今天心情有点烦，能聊聊吗"));
        verify(llmClient, never()).chatWithSystemPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void toolRequestIsNotCapturedByEmotionalFastPath() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("NEED_TOOLS");
        SimpleChatClassifier classifier = new SimpleChatClassifier(llmClient);

        assertFalse(classifier.isSimpleChat("我有点烦，提醒我明天去运动"));
    }
}
