package com.youkeda.exercise.claw.ai.retrieval;

public record SkillKnowledgeSearchResult(
        String chunkId,
        String skillName,
        String documentId,
        String content,
        String contentHash,
        String source,
        String heading,
        Integer pageNumber,
        String version,
        double score
) {
}
