package com.youkeda.exercise.claw.ai.retrieval;

public record SkillKnowledgeConfig(
        boolean enabled,
        int topK,
        float minScore,
        int maxContextChars
) {
    public static SkillKnowledgeConfig disabled() {
        return new SkillKnowledgeConfig(false, 0, 0f, 0);
    }
}
