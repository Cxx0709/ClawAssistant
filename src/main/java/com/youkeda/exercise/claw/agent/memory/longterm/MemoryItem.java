package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/** A durable memory with provenance and a stable semantic topic key. */
public record MemoryItem(
        String id,
        MemoryCategory category,
        String topicKey,
        String content,
        String evidence,
        float importance,
        float confidence,
        MemorySource source,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
        int hitCount,
        boolean disabled,
        String sourceConversationId
) {

    public MemoryItem(String id, MemoryCategory category, String topicKey, String content,
                      String evidence, float importance, float confidence, MemorySource source,
                      Instant createdAt, Instant updatedAt, int hitCount) {
        this(id, category, topicKey, content, evidence, importance, confidence, source,
                createdAt, updatedAt, hitCount, false, null);
    }

    public MemoryItem withDetails(boolean paused, String conversationId) {
        return new MemoryItem(id, category, topicKey, content, evidence, importance, confidence,
                source, createdAt, updatedAt, hitCount, paused, conversationId);
    }

    public Instant nextUpdateTime() {
        return Instant.ofEpochMilli(Math.max(System.currentTimeMillis(), updatedAt.toEpochMilli() + 1));
    }

    public static MemoryItem ofAuto(MemoryCategory category,
                                    String content, float importance) {
        return ofAuto(category, "", content, content, importance, 0.5f);
    }

    public static MemoryItem ofAuto(MemoryCategory category,
                                    String topicKey, String content, String evidence,
                                    float importance, float confidence) {
        Instant now = Instant.now();
        return new MemoryItem(
                UUID.randomUUID().toString(), category,
                normalizeTopicKey(topicKey), content, evidence,
                importance, confidence, MemorySource.AUTO, now, now, 0);
    }

    public static MemoryItem ofManual(String content) {
        return ofManual(MemoryCategory.PREFERENCE, "", content);
    }

    public static MemoryItem ofManual(
            MemoryCategory category, String content) {
        return ofManual(category, "", content);
    }

    public static MemoryItem ofManual(
            MemoryCategory category, String topicKey, String content) {
        Instant now = Instant.now();
        return new MemoryItem(
                UUID.randomUUID().toString(), category,
                normalizeTopicKey(topicKey), content, content,
                1.0f, 1.0f, MemorySource.MANUAL, now, now, 0);
    }

    /** Applies an UPDATE or MERGE decision while retaining stable identity. */
    public MemoryItem withResolvedContent(
            MemoryItem incoming, String resolvedContent, MemoryMergeAction action) {
        MemorySource resolvedSource = source == MemorySource.MANUAL
                || incoming.source == MemorySource.MANUAL
                ? MemorySource.MANUAL : MemorySource.AUTO;
        String resolvedTopicKey = incoming.topicKey == null || incoming.topicKey.isBlank()
                ? topicKey : incoming.topicKey;
        String resolvedEvidence = action == MemoryMergeAction.MERGE
                ? mergeEvidence(evidence, incoming.evidence) : incoming.evidence;
        float resolvedConfidence = action == MemoryMergeAction.MERGE
                ? Math.min(confidence, incoming.confidence) : incoming.confidence;
        return new MemoryItem(
                id, incoming.category, resolvedTopicKey,
                resolvedContent, resolvedEvidence,
                Math.max(importance, incoming.importance),
                resolvedConfidence,
                resolvedSource, createdAt, nextUpdateTime(), hitCount, disabled, incoming.sourceConversationId);
    }

    public MemoryItem withHit() {
        return new MemoryItem(
                id, category, topicKey, content, evidence,
                importance, confidence, source, createdAt, updatedAt, hitCount + 1, disabled, sourceConversationId);
    }

    private static String normalizeTopicKey(String topicKey) {
        return topicKey == null ? "" : topicKey.strip().toLowerCase();
    }

    private static String mergeEvidence(String existing, String incoming) {
        String left = existing == null ? "" : existing.strip();
        String right = incoming == null ? "" : incoming.strip();
        if (left.isBlank()) return right;
        if (right.isBlank() || left.equals(right)) return left;
        String merged = left + "\n" + right;
        return merged.length() <= 1000
                ? merged : merged.substring(merged.length() - 1000);
    }
}
