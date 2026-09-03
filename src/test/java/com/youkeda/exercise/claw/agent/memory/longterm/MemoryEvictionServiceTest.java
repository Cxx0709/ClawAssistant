package com.youkeda.exercise.claw.agent.memory.longterm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 记忆淘汰测试：质量分淘汰、容量上限、边界。
 */
class MemoryEvictionServiceTest {

    private MemoryStore memoryStore;
    private LongTermMemoryProperties props;
    private MemoryEvictionService service;

    @BeforeEach
    void setUp() {
        memoryStore = mock(MemoryStore.class);
        props = new LongTermMemoryProperties();
        service = new MemoryEvictionService(memoryStore, props);
        // 默认 delete 成功
        when(memoryStore.delete(anyString())).thenReturn(true);
    }

    /** 构造一条记忆，指定重要性与更新时间。 */
    private MemoryItem memory(String id, float importance, Instant updatedAt) {
        return new MemoryItem(
                id, MemoryCategory.PREFERENCE, "", "内容", "内容",
                importance, 0.5f, MemorySource.AUTO,
                updatedAt, updatedAt, 0);
    }

    private Instant daysAgo(int days) {
        return Instant.now().minusSeconds(days * 86400L);
    }

    // ==================== evictBelowThreshold ====================

    @Test
    void evictsWeakMemoriesKeepsStrongOnes() {
        // 陈旧低重要（imp=0.3, 500 天前）→ 分数 < 阈值
        MemoryItem weak = memory("weak", 0.3f, daysAgo(500));
        // 新近高重要（imp=0.8, 刚更新）→ 分数 > 阈值
        MemoryItem strong = memory("strong", 0.8f, Instant.now());
        when(memoryStore.getAll()).thenReturn(List.of(weak, strong));

        int deleted = service.evictBelowThreshold();

        assertEquals(1, deleted);
        verify(memoryStore).delete("weak");
        verify(memoryStore, never()).delete("strong");
    }

    @Test
    void freshLowImportanceSurvives() {
        // 新近低重要（imp=0.3, 刚更新）→ recency=1，分数 0.55 > 阈值 → 保留
        MemoryItem fresh = memory("fresh", 0.3f, Instant.now());
        when(memoryStore.getAll()).thenReturn(List.of(fresh));

        assertEquals(0, service.evictBelowThreshold());
        verify(memoryStore, never()).delete(anyString());
    }

    @Test
    void noMemoryToEvictWhenAllAboveThreshold() {
        MemoryItem important = memory("imp", 0.8f, daysAgo(500));
        when(memoryStore.getAll()).thenReturn(List.of(important));

        assertEquals(0, service.evictBelowThreshold());
    }

    @Test
    void disabledFeatureDoesNothing() {
        props.setEnabled(false);
        when(memoryStore.getAll()).thenReturn(List.of(memory("weak", 0.3f, daysAgo(500))));

        assertEquals(0, service.evictBelowThreshold());
        verify(memoryStore, never()).delete(anyString());
    }

    // ==================== evictIfOverCapacity ====================

    @Test
    void evictsWeakestWhenOverCapacity() {
        props.setMaxMemories(2);
        // 3 条：2 新近高重要 + 1 陈旧低重要
        MemoryItem weakest = memory("weakest", 0.3f, daysAgo(500));
        MemoryItem m1 = memory("m1", 0.7f, Instant.now());
        MemoryItem m2 = memory("m2", 0.6f, Instant.now());
        when(memoryStore.count()).thenReturn(3);
        when(memoryStore.getAll()).thenReturn(List.of(weakest, m1, m2));

        int deleted = service.evictIfOverCapacity();

        assertEquals(1, deleted);
        verify(memoryStore).delete("weakest");
        verify(memoryStore, never()).delete("m1");
        verify(memoryStore, never()).delete("m2");
    }

    @Test
    void noEvictionWhenWithinCapacity() {
        props.setMaxMemories(5);
        List<MemoryItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            items.add(memory("m" + i, 0.5f, Instant.now()));
        }
        when(memoryStore.count()).thenReturn(3);

        assertEquals(0, service.evictIfOverCapacity());
        // count() 不超限 → 不 getAll，不 delete
        verify(memoryStore, never()).getAll();
        verify(memoryStore, never()).delete(anyString());
    }

    // ==================== retentionScore ====================

    @Test
    void retentionScoreWeightsImportanceHighest() {
        // 同陈旧度下 importance 主导：陈旧重要 > 陈旧低重要
        MemoryItem oldImportant = memory("a", 0.8f, daysAgo(500));
        MemoryItem oldWeak = memory("b", 0.3f, daysAgo(500));
        assertTrue(service.retentionScore(oldImportant) > service.retentionScore(oldWeak));
    }
}
