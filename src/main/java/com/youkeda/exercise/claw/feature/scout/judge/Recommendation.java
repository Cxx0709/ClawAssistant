package com.youkeda.exercise.claw.feature.scout.judge;

/**
 * 推荐结果
 */
public record Recommendation(
        String id,
        String title,
        String summary,
        String reason,
        String suggestion,
        String source,
        float relevanceScore,
        Tier tier,
        long createdAt
) {
    public enum Tier {
        STRONG,
        DISCOVERY
    }
}
