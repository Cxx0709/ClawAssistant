package com.youkeda.exercise.claw.agent.memory.longterm;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 保留度评分测试：半衰期衰减正确性（分类差异）。
 */
class MemoryRetentionScorerTest {

    private MemoryItem item(MemoryCategory category, Instant updatedAt) {
        return new MemoryItem(
                "id-" + category + "-" + updatedAt.toEpochMilli(),
                category, "", "内容", "内容",
                0.5f, 0.5f, MemorySource.AUTO,
                updatedAt, updatedAt, 0);
    }

    private Instant daysAgo(int days) {
        return Instant.now().minusSeconds(days * 86400L);
    }

    @Test
    void freshMemoryScoresNearOne() {
        MemoryItem item = item(MemoryCategory.PREFERENCE, Instant.now());
        double score = MemoryRetentionScorer.recencyScore(item, Instant.now(), 180);
        assertTrue(score > 0.99, "刚更新的记忆应接近 1，实际 " + score);
    }

    @Test
    void agedMemoryScoresBelowHalf() {
        // 1 个半衰期（180 天）→ 约 0.5
        MemoryItem item = item(MemoryCategory.PREFERENCE, daysAgo(180));
        double score = MemoryRetentionScorer.recencyScore(item, Instant.now(), 180);
        assertTrue(Math.abs(score - 0.5) < 0.05, "180 天 PREFERENCE 应约 0.5，实际 " + score);
    }

    @Test
    void ruleMemoryDecaysSlowerThanPreference() {
        // 同样 500 天，RULE 半衰期 ×10 = 1800 天 → 衰减远小于 PREFERENCE
        MemoryItem rule = item(MemoryCategory.RULE, daysAgo(500));
        MemoryItem pref = item(MemoryCategory.PREFERENCE, daysAgo(500));
        double ruleScore = MemoryRetentionScorer.recencyScore(rule, Instant.now(), 180);
        double prefScore = MemoryRetentionScorer.recencyScore(pref, Instant.now(), 180);
        assertTrue(ruleScore > prefScore,
                "RULE 应比 PREFERENCE 衰减慢，rule=" + ruleScore + " pref=" + prefScore);
    }

    @Test
    void clamp01ClampsValues() {
        assertEquals(0d, MemoryRetentionScorer.clamp01(-0.5f));
        assertEquals(0.5d, MemoryRetentionScorer.clamp01(0.5f), 0.0001);
        assertEquals(1d, MemoryRetentionScorer.clamp01(1.5f));
    }
}
