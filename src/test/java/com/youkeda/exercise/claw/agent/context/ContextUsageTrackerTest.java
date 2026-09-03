package com.youkeda.exercise.claw.agent.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L1 上下文占用埋点聚合逻辑测试。
 */
class ContextUsageTrackerTest {

    @Test
    void averageUsedTokensAggregates() {
        ContextUsageTracker tracker = new ContextUsageTracker(0);
        tracker.recordBuild(100);
        tracker.recordBuild(200);
        tracker.recordBuild(300);

        ContextUsageTracker.Snapshot s = tracker.snapshot();
        assertEquals(3, s.buildCount());
        assertEquals(600, s.totalUsedTokens());
        assertEquals(200.0, s.averageUsedTokens());
    }

    @Test
    void summaryCompressionAggregates() {
        ContextUsageTracker tracker = new ContextUsageTracker(0);
        tracker.recordSummary(1000, 200);
        tracker.recordSummary(500, 100);

        ContextUsageTracker.Snapshot s = tracker.snapshot();
        assertEquals(2, s.summaryCount());
        assertEquals(1500, s.totalRawTokens());
        assertEquals(300, s.totalSummaryTokens());
        assertEquals(1500, s.totalAvoidedTokens());
        // (1500-300)/1500 = 80%
        assertEquals(80.0, s.avgSummaryCompressionPercent());
    }

    @Test
    void emptySnapshotReturnsZero() {
        ContextUsageTracker.Snapshot s = new ContextUsageTracker(0).snapshot();
        assertEquals(0, s.averageUsedTokens());
        assertEquals(0, s.avgSummaryCompressionPercent());
        assertEquals(0, s.totalAvoidedTokens());
    }

    @Test
    void recordBuildAndSummaryAreIndependent() {
        ContextUsageTracker tracker = new ContextUsageTracker(0);
        tracker.recordBuild(50);
        tracker.recordSummary(800, 100);

        ContextUsageTracker.Snapshot s = tracker.snapshot();
        assertEquals(1, s.buildCount());
        assertEquals(1, s.summaryCount());
        assertEquals(50.0, s.averageUsedTokens());
        assertEquals(87.5, s.avgSummaryCompressionPercent());
    }
}
