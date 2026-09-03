package com.youkeda.exercise.claw.agent.memory.longterm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 长期记忆模块总配置
 */
@Component
@ConfigurationProperties(prefix = "memory")
public class LongTermMemoryProperties {

    /** 是否启用长期记忆 */
    private boolean enabled = true;

    /** 记忆重要性阈值：低于此值不入库（0.0 ~ 1.0） */
    private float importanceThreshold = 0.3f;

    /** 每次召回的记忆条数（Top-K） */
    private int recallTopK = 5;

    /** 召回最低余弦相似度，避免不相关记忆仅因 Top-K 被注入。 */
    private float recallMinScore = 0.45f;

    /** 重排前的候选数量倍数。 */
    private int recallCandidateMultiplier = 3;

    /** 普通记忆的新鲜度半衰期（天）；规则和事实使用更长半衰期。 */
    private int recencyHalfLifeDays = 180;

    /** 单用户场景下默认一个常驻记忆处理线程。 */
    private int asyncCoreSize = 1;

    private int asyncMaxSize = 2;

    private int asyncQueueCapacity = 50;

    /** 去重相似度阈值：高于此值视为重复记忆 */
    private float dedupSimilarity = 0.90f;

    /** 用户消息最短长度：低于此值跳过记忆提取 */
    private int minExtractLength = 10;

    /** 记忆容量上限：超限时按质量分淘汰最弱 */
    private int maxMemories = 500;

    /** 质量分阈值：低于此值的记忆淘汰。
     * <p>baseline（imp=0.3 门槛 + conf=0.5 默认）= 0.25，须高于 baseline 才能淘汰
     * 「陈旧低重要」记忆（其分数约 0.29），同时保留「陈旧重要」（≈0.50）与「新近低重要」（≈0.55）。 */
    private float minRetentionScore = 0.30f;

    /** 定时淘汰 cron（默认凌晨 3 点） */
    private String evictionCron = "0 0 3 * * *";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getImportanceThreshold() {
        return importanceThreshold;
    }

    public void setImportanceThreshold(float importanceThreshold) {
        this.importanceThreshold = importanceThreshold;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
        this.recallTopK = recallTopK;
    }

    public float getRecallMinScore() {
        return recallMinScore;
    }

    public void setRecallMinScore(float recallMinScore) {
        this.recallMinScore = recallMinScore;
    }

    public int getRecallCandidateMultiplier() {
        return recallCandidateMultiplier;
    }

    public void setRecallCandidateMultiplier(int recallCandidateMultiplier) {
        this.recallCandidateMultiplier = recallCandidateMultiplier;
    }

    public int getRecencyHalfLifeDays() {
        return recencyHalfLifeDays;
    }

    public void setRecencyHalfLifeDays(int recencyHalfLifeDays) {
        this.recencyHalfLifeDays = recencyHalfLifeDays;
    }

    public int getAsyncCoreSize() {
        return asyncCoreSize;
    }

    public void setAsyncCoreSize(int asyncCoreSize) {
        this.asyncCoreSize = asyncCoreSize;
    }

    public int getAsyncMaxSize() {
        return asyncMaxSize;
    }

    public void setAsyncMaxSize(int asyncMaxSize) {
        this.asyncMaxSize = asyncMaxSize;
    }

    public int getAsyncQueueCapacity() {
        return asyncQueueCapacity;
    }

    public void setAsyncQueueCapacity(int asyncQueueCapacity) {
        this.asyncQueueCapacity = asyncQueueCapacity;
    }

    public float getDedupSimilarity() {
        return dedupSimilarity;
    }

    public void setDedupSimilarity(float dedupSimilarity) {
        this.dedupSimilarity = dedupSimilarity;
    }

    public int getMinExtractLength() {
        return minExtractLength;
    }

    public void setMinExtractLength(int minExtractLength) {
        this.minExtractLength = minExtractLength;
    }

    public int getMaxMemories() {
        return maxMemories;
    }

    public void setMaxMemories(int maxMemories) {
        this.maxMemories = maxMemories;
    }

    public float getMinRetentionScore() {
        return minRetentionScore;
    }

    public void setMinRetentionScore(float minRetentionScore) {
        this.minRetentionScore = minRetentionScore;
    }

    public String getEvictionCron() {
        return evictionCron;
    }

    public void setEvictionCron(String evictionCron) {
        this.evictionCron = evictionCron;
    }
}
