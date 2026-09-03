package com.youkeda.exercise.claw.feature.scout.matcher;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.context.UserProfile;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CandidateMatcherFallbackTest {

    @Test
    void includesExplicitTopicInMatchingText() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> new float[]{1f, 0f}).toList();
        });
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, new ScoutProperties());
        InformationItem item = item("PyTorch release", new float[]{1f, 0f});
        UserProfile profile = new UserProfile(
                List.of("AI"), List.of(), List.of(), List.of(), "");

        matcher.match(profile, "PyTorch 最新版本", List.of(item));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embedBatch(texts.capture());
        assertTrue(texts.getValue().stream().anyMatch(text -> text.contains("PyTorch 最新版本")));
        assertTrue(texts.getValue().stream().anyMatch(text -> text.contains("AI")));
    }

    @Test
    void explicitTopicIsAHardGateBeforeProfileRanking() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(
                new float[]{1f, 0f},
                new float[]{0f, 1f}));
        ScoutProperties properties = new ScoutProperties();
        properties.setMinMatchScore(0.45f);
        properties.setFallbackMatchScore(0.30f);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile profile = new UserProfile(
                List.of("Java 项目"), List.of(), List.of(), List.of(), "");
        InformationItem topicRelevant = item(
                "考研政策调整", new float[]{0.60f, 0.80f});
        InformationItem profileOnly = item(
                "Java 框架更新", new float[]{0.20f, 0.98f});

        List<MatchedCandidate> candidates = matcher.match(
                profile, "考研政策最近变化", List.of(profileOnly, topicRelevant));

        assertEquals(1, candidates.size());
        assertSame(topicRelevant, candidates.get(0).item());
        assertTrue(candidates.get(0).matchReason().contains("本次主题"));
    }

    @Test
    void fallsBackToHighestScoringItemsWhenThresholdMatchesNothing() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(new float[]{1f, 0f}));
        ScoutProperties properties = new ScoutProperties();
        properties.setMinMatchScore(0.60f);
        properties.setFallbackMatchScore(0.30f);
        properties.setFallbackCandidateCount(1);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        InformationItem higher = item("higher", new float[]{0.4f, 0.9165f});
        InformationItem lower = item("lower", new float[]{0.2f, 0.9799f});
        UserProfile profile = new UserProfile(
                List.of("AI"), List.of(), List.of(), List.of(), "");

        List<MatchedCandidate> candidates = matcher.match(profile, List.of(lower, higher));

        assertEquals(1, candidates.size());
        assertSame(higher, candidates.get(0).item());
        assertEquals(0.4f, candidates.get(0).semanticScore(), 0.001f);
    }

    @Test
    void supplementsFewStrictMatchesWithLimitedFallbackCandidates() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(new float[]{1f, 0f}));
        ScoutProperties properties = new ScoutProperties();
        properties.setMinMatchScore(0.45f);
        properties.setFallbackMatchScore(0.30f);
        properties.setFallbackCandidateCount(2);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile profile = new UserProfile(
                List.of("AI"), List.of(), List.of(), List.of(), "");

        List<MatchedCandidate> candidates = matcher.match(profile, List.of(
                item("strict", new float[]{0.60f, 0.80f}),
                item("fallback-high", new float[]{0.44f, 0.898f}),
                item("fallback-low", new float[]{0.35f, 0.937f}),
                item("irrelevant", new float[]{0.20f, 0.980f})));

        assertEquals(3, candidates.size());
        assertEquals("strict", candidates.get(0).item().getTitle());
        assertEquals("fallback-high", candidates.get(1).item().getTitle());
        assertEquals("fallback-low", candidates.get(2).item().getTitle());
    }

    @Test
    void matchesAgainstBestIndividualProfileFacet() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(List.of("兴趣：考研", "兴趣：AI应用开发")))
                .thenReturn(List.of(new float[]{1f, 0f}, new float[]{0f, 1f}));
        ScoutProperties properties = new ScoutProperties();
        properties.setMinMatchScore(0.80f);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile profile = new UserProfile(
                List.of("考研", "AI应用开发"), List.of(), List.of(), List.of(), "");
        InformationItem item = item("AI Agent 框架发布", new float[]{0f, 1f});

        List<MatchedCandidate> candidates = matcher.match(profile, List.of(item));

        assertEquals(1, candidates.size());
        assertEquals(1f, candidates.get(0).semanticScore());
        assertEquals("匹配画像维度：兴趣：AI应用开发", candidates.get(0).matchReason());
    }

    @Test
    void excludesOldAndUndatedItems() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ScoutProperties properties = new ScoutProperties();
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile emptyProfile = new UserProfile(
                List.of(), List.of(), List.of(), List.of(), "");
        InformationItem recent = item("recent", new float[]{1f, 0f});
        InformationItem old = item("old", new float[]{1f, 0f});
        old.setPublishedAt(Instant.now().minus(60, ChronoUnit.DAYS).toEpochMilli());
        InformationItem undated = item("undated", new float[]{1f, 0f});
        undated.setPublishedAt(0L);

        List<MatchedCandidate> candidates = matcher.match(
                emptyProfile, List.of(recent, old, undated));

        assertEquals(1, candidates.size());
        assertSame(recent, candidates.get(0).item());
    }

    @Test
    void fallsBackToKeywordMatchingWhenEmbeddingThrows() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList()))
                .thenThrow(new IllegalStateException("Embedding service is not available"));
        ScoutProperties properties = new ScoutProperties();
        properties.setMaxCandidates(10);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile profile = new UserProfile(
                List.of("AI Agent"), List.of(), List.of(), List.of(), "");
        InformationItem relevant = item("AI Agent 框架发布", new float[0]);
        InformationItem irrelevant = item("考研政策调整", new float[0]);

        List<MatchedCandidate> candidates = matcher.match(
                profile, List.of(relevant, irrelevant));

        assertEquals(1, candidates.size());
        assertSame(relevant, candidates.get(0).item());
        assertTrue(candidates.get(0).matchReason().contains("关键词匹配"));
    }

    @Test
    void keywordFallbackFiltersOutIrrelevantItems() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedBatch(anyList()))
                .thenThrow(new IllegalStateException("Embedding service is not available"));
        ScoutProperties properties = new ScoutProperties();
        properties.setMaxCandidates(10);
        CandidateMatcher matcher = new CandidateMatcher(embeddingClient, properties);
        UserProfile profile = new UserProfile(
                List.of("自动驾驶"), List.of(), List.of(), List.of(), "");

        List<MatchedCandidate> candidates = matcher.match(profile, List.of(
                item("自动驾驶技术最新进展", new float[0]),
                item("餐饮行业分析", new float[0]),
                item("演唱会门票开售", new float[0])));

        assertEquals(1, candidates.size());
        assertEquals("自动驾驶技术最新进展", candidates.get(0).item().getTitle());
    }

    private InformationItem item(String title, float[] vector) {
        InformationItem item = InformationItem.create(title, title, "https://example.com/" + title,
                "WEB_SEARCH", "NEWS");
        item.setVector(vector);
        item.setPublishedAt(Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli());
        return item;
    }
}
