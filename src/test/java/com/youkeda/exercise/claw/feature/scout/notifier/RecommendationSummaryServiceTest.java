package com.youkeda.exercise.claw.feature.scout.notifier;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.scout.judge.Recommendation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RecommendationSummaryServiceTest {

    @Test
    void generatesCrossItemSummaryWithAStableHeading() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(900)))
                .thenReturn("本轮主要关注 AI 工作方式和基础设施两个方向。");
        RecommendationSummaryService service = new RecommendationSummaryService(llmClient);

        String summary = service.summarize(List.of(
                recommendation("AI 工作方式", Recommendation.Tier.STRONG),
                recommendation("AI 基础设施", Recommendation.Tier.DISCOVERY)));

        assertTrue(summary.startsWith("📌 信息猎手总结"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(anyString(), prompt.capture(), eq(900));
        assertTrue(prompt.getValue().contains("AI 工作方式"));
        assertTrue(prompt.getValue().contains("AI 基础设施"));
    }

    @Test
    void fallsBackToLocalSummaryWhenModelFails() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(900)))
                .thenThrow(new IllegalStateException("unavailable"));
        RecommendationSummaryService service = new RecommendationSummaryService(llmClient);

        String summary = service.summarize(List.of(
                recommendation("优先阅读主题", Recommendation.Tier.STRONG)));

        assertTrue(summary.contains("📌 信息猎手总结"));
        assertTrue(summary.contains("优先阅读主题"));
    }

    @Test
    void retriesEmptySummaryWithLargerOutputBudget() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(900)))
                .thenReturn("");
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(1400)))
                .thenReturn("综合后应优先关注 AI 工具落地与工程实践。");
        RecommendationSummaryService service = new RecommendationSummaryService(llmClient);

        String summary = service.summarize(List.of(
                recommendation("AI 工程实践", Recommendation.Tier.STRONG)));

        assertTrue(summary.contains("AI 工具落地与工程实践"));
        verify(llmClient).chatWithSystemPrompt(anyString(), anyString(), eq(900));
        verify(llmClient).chatWithSystemPrompt(anyString(), anyString(), eq(1400));
    }

    private Recommendation recommendation(String title, Recommendation.Tier tier) {
        return new Recommendation(
                title, title, "摘要", "推荐理由", "阅读原文",
                "https://example.com/" + title, 0.6f, tier, System.currentTimeMillis());
    }
}
