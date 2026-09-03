package com.youkeda.exercise.claw.feature.scout.notifier;

import com.youkeda.exercise.claw.feature.scout.judge.Recommendation;
import com.youkeda.exercise.claw.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceDeliveryTest {

    @Test
    void persistsConciseFailureNotification() {
        NotificationSink sink = mock(NotificationSink.class);
        NotificationService service = new NotificationService(
                sink, mock(RecommendationSummaryService.class));

        service.notifyFailure();

        verify(sink).publishToAll(eq("SCOUT"), contains("失败"),
                eq("信息猎手本次运行失败，请稍后重试。"), anyInt(), isNull());
    }

    @Test
    void persistsDetailAndSummaryAsSeparateNotifications() {
        NotificationSink sink = mock(NotificationSink.class);
        RecommendationSummaryService summaries = mock(RecommendationSummaryService.class);
        when(summaries.summarize(anyList())).thenReturn("📌 信息猎手总结\n\n综合结论");
        NotificationService service = new NotificationService(sink, summaries);

        service.notifyWithSummary(List.of(
                new Recommendation("strong", "强推荐", "summary", "reason", "suggestion",
                        "https://example.com/strong", 0.55f,
                        Recommendation.Tier.STRONG, System.currentTimeMillis()),
                new Recommendation("scan", "可浏览", "summary", "reason", "suggestion",
                        "https://example.com/scan", 0.90f,
                        Recommendation.Tier.DISCOVERY, System.currentTimeMillis())));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sink, times(2)).publishToAll(eq("SCOUT"), anyString(),
                content.capture(), anyInt(), isNull());
        assertTrue(content.getAllValues().get(0).contains("🔥 强推荐"));
        assertTrue(content.getAllValues().get(0).contains("👀 值得扫一眼"));
        assertTrue(content.getAllValues().get(1).contains("📌 信息猎手总结"));
    }

    @Test
    void splitsLongDetailAtRecommendationBoundaries() {
        NotificationSink sink = mock(NotificationSink.class);
        RecommendationSummaryService summaries = mock(RecommendationSummaryService.class);
        when(summaries.summarize(anyList())).thenReturn("总结");
        NotificationService service = new NotificationService(sink, summaries);
        String longSummary = "这是一段较长的推荐摘要，用于验证长文本会按完整条目分段发送。".repeat(12);
        List<Recommendation> recommendations = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new Recommendation(
                        "rec-" + index, "推荐标题 " + index, longSummary,
                        "匹配用户画像", "阅读原文", "https://example.com/" + index,
                        0.6f, Recommendation.Tier.DISCOVERY, System.currentTimeMillis()))
                .toList();

        service.notifyWithSummary(recommendations);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sink, atLeast(3)).publishToAll(eq("SCOUT"), anyString(),
                content.capture(), anyInt(), isNull());
        List<String> values = content.getAllValues();
        assertTrue(values.stream().limit(values.size() - 1L).allMatch(value -> value.length() <= 1800));
    }
}
