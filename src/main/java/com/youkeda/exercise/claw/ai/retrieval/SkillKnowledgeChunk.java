package com.youkeda.exercise.claw.ai.retrieval;

public record SkillKnowledgeChunk(
        String chunkId,
        String skillName,
        String documentId,
        int chunkIndex,
        String content,
        String contentHash,
        String source,
        String heading,
        Integer pageNumber,
        String version,
        boolean enabled
) {
}
