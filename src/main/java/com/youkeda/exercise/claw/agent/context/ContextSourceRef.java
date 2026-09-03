package com.youkeda.exercise.claw.agent.context;

/**
 * 单个来源的溯源引用（ADR §5 的 ContextMetadata.sources）。
 */
public record ContextSourceRef(ContextSource source, int estimatedTokens) {
}
