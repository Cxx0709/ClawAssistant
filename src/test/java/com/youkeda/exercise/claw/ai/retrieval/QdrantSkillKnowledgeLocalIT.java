package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.QdrantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in verification against a developer's local Qdrant instance. */
@EnabledIfSystemProperty(named = "local.qdrant.it", matches = "true")
class QdrantSkillKnowledgeLocalIT {

    @Test
    void verifiesRealUpsertSearchSoftDeleteAndHardDelete() throws Exception {
        String collection = "skill_knowledge_it_" + UUID.randomUUID().toString().replace("-", "");
        QdrantProperties properties = new QdrantProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(6334);
        QdrantClientProvider provider = new QdrantClientProvider(properties);
        provider.init();
        QdrantSkillKnowledgeStore store = new QdrantSkillKnowledgeStore(provider);
        ReflectionTestUtils.setField(store, "collectionName", collection);
        ReflectionTestUtils.setField(store, "vectorDimension", 3);
        ReflectionTestUtils.setField(store, "operationTimeout", Duration.ofSeconds(5));

        try {
            store.init();
            assertThrows(IllegalArgumentException.class, () -> store.search(
                    new float[]{1f, 0f, 0f}, Set.of(), 5, 0f));
            SkillKnowledgeChunk chunk = new SkillKnowledgeChunk(
                    UUID.randomUUID().toString(), "travel-planning", "local-doc", 0,
                    "local integration content", "hash", "local-it", "root", null,
                    "1.0", false);
            store.upsertAll(List.of(new SkillKnowledgeVector(chunk, new float[]{1f, 0f, 0f})));
            assertTrue(store.search(new float[]{1f, 0f, 0f},
                    Set.of("travel-planning"), 5, 0f).isEmpty());
            assertEquals(1, store.setDocumentEnabled("travel-planning", "local-doc", true));

            List<SkillKnowledgeSearchResult> results = store.search(
                    new float[]{1f, 0f, 0f}, Set.of("travel-planning"), 5, 0f);
            assertEquals(1, results.size());
            assertTrue(store.status("travel-planning").available());
            assertEquals(1, store.softDeleteByDocument("travel-planning", "local-doc"));
            assertEquals(0, store.status("travel-planning").pointCount());
            assertTrue(store.search(new float[]{1f, 0f, 0f},
                    Set.of("travel-planning"), 5, 0f).isEmpty());
            assertEquals(1, store.hardDeleteByDocument("travel-planning", "local-doc"));
        } finally {
            provider.getClient().deleteCollectionAsync(collection, Duration.ofSeconds(5)).get();
            provider.close();
        }
    }
}
