package com.youkeda.exercise.claw.agent.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 长期记忆淘汰服务（ADR §9/Phase 4）。
 *
 * <p>让记忆集合「有界」：质量分淘汰弱记忆 + 容量上限兜底。
 * <ul>
 *   <li>{@link #evictBelowThreshold()} — 淘汰质量分低于 minRetentionScore 的弱记忆</li>
 *   <li>{@link #evictIfOverCapacity()} — 超 maxMemories 时按质量分升序淘汰最弱</li>
 * </ul>
 *
 * <p>触发：每天定时（{@code memory.eviction-cron}）+ 写入后检查（由 LongTermMemoryService 调）。
 * 质量分不含语义相似度（淘汰无查询向量），由 importance + confidence + recency 决定。
 */
@Component
public class MemoryEvictionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEvictionService.class);

    private final MemoryStore memoryStore;
    private final LongTermMemoryProperties props;

    public MemoryEvictionService(MemoryStore memoryStore, LongTermMemoryProperties props) {
        this.memoryStore = memoryStore;
        this.props = props;
    }

    /**
     * 每天定时淘汰：质量分淘汰 + 容量兜底。
     * 由 {@code memory.eviction-cron} 配置（默认凌晨 3 点）。
     */
    public void scheduledEviction() {
        log.info("记忆定时淘汰开始");
        evictBelowThreshold();
        evictIfOverCapacity();
    }

    /**
     * 淘汰质量分低于 minRetentionScore 的弱记忆。
     *
     * @return 淘汰条数
     */
    public int evictBelowThreshold() {
        if (!props.isEnabled()) return 0;
        float threshold = props.getMinRetentionScore();
        List<MemoryItem> all = memoryStore.getAll();
        List<MemoryItem> toEvict = all.stream()
                .filter(item -> retentionScore(item) < threshold)
                .toList();
        return evict(toEvict);
    }

    /**
     * 容量上限兜底：总记忆超 maxMemories 时，按质量分升序淘汰最弱，直到达标。
     *
     * @return 淘汰条数
     */
    public int evictIfOverCapacity() {
        if (!props.isEnabled()) return 0;
        int max = props.getMaxMemories();
        if (max <= 0) return 0;

        // 轻量检查：count() 不超限直接返回（写后检查多数时候零成本）
        if (memoryStore.count() <= max) {
            return 0;
        }
        // 超限：全量拉取，按质量分升序（最弱在前）淘汰最弱的 (size - max) 条
        List<MemoryItem> all = memoryStore.getAll();
        if (all.size() <= max) {
            return 0;
        }
        List<MemoryItem> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(this::retentionScore));
        int excess = sorted.size() - max;
        List<MemoryItem> toEvict = new ArrayList<>(sorted.subList(0, excess));
        return evict(toEvict);
    }

    /**
     * 记忆质量分：0.5×importance + 0.2×confidence + 0.3×recency。
     * importance 主导，避免误杀重要记忆；不含 hitCount（见 ADR 决策）。
     */
    double retentionScore(MemoryItem item) {
        double importance = MemoryRetentionScorer.clamp01(item.importance());
        double confidence = MemoryRetentionScorer.clamp01(item.confidence());
        double recency = MemoryRetentionScorer.recencyScore(
                item, Instant.now(), props.getRecencyHalfLifeDays());
        return 0.5d * importance + 0.2d * confidence + 0.3d * recency;
    }

    private int evict(List<MemoryItem> toEvict) {
        if (toEvict.isEmpty()) return 0;
        int deleted = 0;
        for (MemoryItem item : toEvict) {
            if (memoryStore.delete(item.id())) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("记忆淘汰完成 | 淘汰 {} 条", deleted);
        }
        return deleted;
    }
}
