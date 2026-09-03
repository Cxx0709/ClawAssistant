package com.youkeda.exercise.claw.agent.memory.longterm;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆保留度评分工具（ADR §9/Phase 4）。
 *
 * <p>提取自 {@code LongTermMemoryService} 的召回新鲜度因子，供召回重排与淘汰复用
 * （单一事实源）。半衰期按分类差异化：
 * <ul>
 *   <li>RULE / FACT — 长半衰期（×10），规则和事实长期有效</li>
 *   <li>GOAL — 短半衰期（/2），目标随时间推移需重新确认</li>
 *   <li>PREFERENCE / EXPERIENCE — 基准半衰期</li>
 * </ul>
 */
final class MemoryRetentionScorer {

    private MemoryRetentionScorer() {
    }

    /**
     * 新鲜度分数：0（很久前）~ 1（刚更新），指数半衰期衰减。
     *
     * @param baseHalfLifeDays 基准半衰期天数（来自配置 recencyHalfLifeDays）
     */
    static double recencyScore(MemoryItem item, Instant now, int baseHalfLifeDays) {
        long ageDays = Math.max(0L, Duration.between(item.updatedAt(), now).toDays());
        int baseHalfLife = Math.max(1, baseHalfLifeDays);
        int halfLife = switch (item.category()) {
            case RULE, FACT -> baseHalfLife * 10;
            case GOAL -> Math.max(30, baseHalfLife / 2);
            case PREFERENCE, EXPERIENCE -> baseHalfLife;
        };
        return Math.exp(-Math.log(2d) * ageDays / halfLife);
    }

    /** 把重要性/置信度钳制到 [0, 1]。 */
    static double clamp01(float value) {
        return Math.max(0d, Math.min(1d, value));
    }
}
