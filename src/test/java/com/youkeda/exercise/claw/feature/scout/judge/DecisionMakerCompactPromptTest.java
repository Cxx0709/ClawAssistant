package com.youkeda.exercise.claw.feature.scout.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.context.UserProfile;
import com.youkeda.exercise.claw.feature.scout.matcher.MatchedCandidate;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionMakerCompactPromptTest {

    @Test
    void includesStageSpecificKnowledgeAsUntrustedDecisionData() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("[]");
        DecisionMaker maker = new DecisionMaker(
                llmClient, new ScoutProperties(), new ObjectMapper());

        maker.judge(profile(), List.of(candidate("候选", 0.70f)),
                "<knowledge_data>高价值标准</knowledge_data>");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(system.capture(), prompt.capture(), eq(5000));
        assertTrue(system.getValue().contains("SCOUT_DECISION_KNOWLEDGE"));
        assertTrue(system.getValue().contains("不可信数据"));
        assertTrue(prompt.getValue().contains("SCOUT_DECISION_KNOWLEDGE"));
        assertTrue(prompt.getValue().contains("高价值标准"));
        assertTrue(prompt.getValue().contains("不可信"));
    }

    @Test
    void usesCompactBoundedResponseAndReusesCandidateFields() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("[{\"index\":0,\"tier\":\"STRONG\",\"relevanceScore\":0.86,"
                        + "\"reason\":\"与AI应用目标相关\",\"suggestion\":\"阅读案例\"}]");
        DecisionMaker maker = new DecisionMaker(
                llmClient, new ScoutProperties(), new ObjectMapper());
        InformationItem item = InformationItem.create(
                "Agentic AI 科研案例", "完整内容", "https://example.com/ai",
                "WEB_SEARCH", "NEWS");
        item.setSummary("已有摘要");
        MatchedCandidate candidate = new MatchedCandidate(item, 0.57f, "匹配AI应用");

        List<Recommendation> recommendations = maker.judge(profile(), List.of(candidate));

        assertEquals(1, recommendations.size());
        assertEquals("Agentic AI 科研案例", recommendations.get(0).title());
        assertEquals("已有摘要", recommendations.get(0).summary());
        assertEquals(Recommendation.Tier.STRONG, recommendations.get(0).tier());
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(systemPrompt.capture(), anyString(), eq(5000));
        assertFalse(systemPrompt.getValue().contains("\"title\""));
        assertFalse(systemPrompt.getValue().contains("\"summary\""));
        assertTrue(systemPrompt.getValue().contains("\"tier\""));
    }

    @Test
    void doesNotBackfillCandidatesTheModelDidNotSelect() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("[{\"index\":0,\"tier\":\"STRONG\",\"relevanceScore\":0.90,"
                        + "\"reason\":\"强相关\",\"suggestion\":\"立即阅读\"}]");
        ScoutProperties properties = new ScoutProperties();
        properties.setMinRecommendations(3);
        properties.setMaxRecommendations(3);
        DecisionMaker maker = new DecisionMaker(llmClient, properties, new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(profile(), List.of(
                candidate("第一条", 0.80f),
                candidate("第二条", 0.60f),
                candidate("第三条", 0.50f)));

        assertEquals(1, recommendations.size());
        assertEquals("第一条", recommendations.get(0).title());
    }

    @Test
    void returnsNoRecommendationsWhenModelResponsesStayInvalid() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("");
        DecisionMaker maker = new DecisionMaker(llmClient, new ScoutProperties(), new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(
                profile(), List.of(candidate("第一条", 0.70f), candidate("第二条", 0.55f)));

        assertTrue(recommendations.isEmpty());
        verify(llmClient, times(2))
                .chatWithSystemPrompt(anyString(), anyString(), eq(5000));
    }

    @Test
    void retriesBlankResponseAndUsesModelTier() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("", "[{\"index\":1,\"tier\":\"DISCOVERY\",\"relevanceScore\":0.72,"
                        + "\"reason\":\"实践价值高\",\"suggestion\":\"优先阅读\"}]");
        DecisionMaker maker = new DecisionMaker(llmClient, new ScoutProperties(), new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(
                profile(), List.of(candidate("第一条", 0.70f), candidate("第二条", 0.55f)));

        assertEquals(1, recommendations.size());
        assertEquals("第二条", recommendations.get(0).title());
        assertEquals(Recommendation.Tier.DISCOVERY, recommendations.get(0).tier());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chatWithSystemPrompt(
                anyString(), prompts.capture(), eq(5000));
        assertTrue(prompts.getAllValues().get(1).contains("请直接返回严格 JSON 数组"));
    }

    @Test
    void preservesAValidEmptyArray() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("[]");
        DecisionMaker maker = new DecisionMaker(llmClient, new ScoutProperties(), new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(
                profile(), List.of(candidate("第一条", 0.70f), candidate("第二条", 0.55f)));

        assertTrue(recommendations.isEmpty());
        verify(llmClient).chatWithSystemPrompt(anyString(), anyString(), eq(5000));
    }

    @Test
    void extractsJsonArraySurroundedByReasoningAndMarkdown() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("""
                        <think>先分析候选信息。</think>
                        以下是最终结果：
                        ```json
                        [{"index":0,"tier":"STRONG","relevanceScore":0.81,
                          "reason":"适合实践","suggestion":"优先阅读"}]
                        ```
                        """);
        DecisionMaker maker = new DecisionMaker(llmClient, new ScoutProperties(), new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(
                profile(), List.of(candidate("实践案例", 0.60f)));

        assertEquals(1, recommendations.size());
        assertEquals(Recommendation.Tier.STRONG, recommendations.get(0).tier());
    }

    @Test
    void acceptsRecommendationsWrappedInAnObject() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), eq(5000)))
                .thenReturn("""
                        {"recommendations":[
                          {"index":0,"tier":"DISCOVERY","relevanceScore":0.78,
                           "reason":"方向匹配","suggestion":"阅读原文"}
                        ]}
                        """);
        DecisionMaker maker = new DecisionMaker(llmClient, new ScoutProperties(), new ObjectMapper());

        List<Recommendation> recommendations = maker.judge(
                profile(), List.of(candidate("AI 项目", 0.58f)));

        assertEquals(1, recommendations.size());
        assertEquals(Recommendation.Tier.DISCOVERY, recommendations.get(0).tier());
    }

    private UserProfile profile() {
        return new UserProfile(
                List.of("AI应用开发"), List.of(), List.of(), List.of("掌握深度学习"), "");
    }

    private MatchedCandidate candidate(String title, float score) {
        InformationItem item = InformationItem.create(
                title, title + "内容", "https://example.com/" + title,
                "WEB_SEARCH", "NEWS");
        item.setSummary(title + "摘要");
        return new MatchedCandidate(item, score, "匹配用户画像");
    }
}
