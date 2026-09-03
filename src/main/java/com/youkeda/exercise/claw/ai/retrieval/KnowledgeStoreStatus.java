package com.youkeda.exercise.claw.ai.retrieval;

/** Real status surfaced by the knowledge management tool. */
public record KnowledgeStoreStatus(
        boolean available,
        String collection,
        long pointCount,
        String message,
        Boolean globalEnabled,
        Boolean skillKnowledgeEnabled,
        String embeddingCircuitState
) {
    public KnowledgeStoreStatus(boolean available,
                                String collection,
                                long pointCount,
                                String message) {
        this(available, collection, pointCount, message, null, null, "UNKNOWN");
    }
}
