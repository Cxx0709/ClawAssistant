package com.youkeda.exercise.claw.agent.memory.longterm;

/** A memory together with its vector-search relevance score. */
public record MemorySearchResult(MemoryItem item, float semanticScore) {
}
