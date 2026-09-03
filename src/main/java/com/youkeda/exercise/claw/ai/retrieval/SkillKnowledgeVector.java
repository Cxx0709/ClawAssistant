package com.youkeda.exercise.claw.ai.retrieval;

public record SkillKnowledgeVector(SkillKnowledgeChunk chunk, float[] vector) {
    public SkillKnowledgeVector {
        if (chunk == null) throw new IllegalArgumentException("chunk is required");
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector is required");
        }
    }
}
