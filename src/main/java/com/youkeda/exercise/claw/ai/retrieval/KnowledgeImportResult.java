package com.youkeda.exercise.claw.ai.retrieval;

public record KnowledgeImportResult(
        String status,
        String skillName,
        String documentId,
        String source,
        String version,
        int chunkCount,
        int successCount,
        String error
) {
    /** Backward-compatible constructor for tests and older callers. */
    public KnowledgeImportResult(String status,
                                 String documentId,
                                 int totalChunks,
                                 int successCount,
                                 String error) {
        this(status, "", documentId, "", "", totalChunks, successCount, error);
    }

    public int totalChunks() {
        return chunkCount;
    }
}
