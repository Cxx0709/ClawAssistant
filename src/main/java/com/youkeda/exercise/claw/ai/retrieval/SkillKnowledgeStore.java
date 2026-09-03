package com.youkeda.exercise.claw.ai.retrieval;

import java.util.List;
import java.util.Set;

public interface SkillKnowledgeStore {

    void upsertAll(List<SkillKnowledgeVector> points);

    default void upsert(SkillKnowledgeChunk chunk, float[] vector) {
        upsertAll(List.of(new SkillKnowledgeVector(chunk, vector)));
    }

    List<SkillKnowledgeSearchResult> search(
            float[] queryVector,
            Set<String> skillNames,
            int topK,
            float minScore
    );

    long setDocumentEnabled(String skillName, String documentId, boolean enabled);

    long softDeleteByDocument(String skillName, String documentId);

    long hardDeleteByDocument(String skillName, String documentId);

    long countByDocument(String skillName, String documentId);

    KnowledgeStoreStatus status(String skillName);
}
