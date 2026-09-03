package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.QdrantProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class QdrantSkillKnowledgeStoreTest {

    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>(
            DockerImageName.parse("qdrant/qdrant:v1.13.4"))
            .withExposedPorts(6334);

    private static QdrantClientProvider provider;
    private static QdrantSkillKnowledgeStore store;

    @BeforeAll
    static void setUpStore() {
        QdrantProperties properties = new QdrantProperties();
        properties.setHost(QDRANT.getHost());
        properties.setPort(QDRANT.getMappedPort(6334));
        provider = new QdrantClientProvider(properties);
        provider.init();

        store = new QdrantSkillKnowledgeStore(provider);
        ReflectionTestUtils.setField(store, "collectionName", "skill_knowledge_test_" + UUID.randomUUID());
        ReflectionTestUtils.setField(store, "vectorDimension", 3);
        ReflectionTestUtils.setField(store, "operationTimeout", Duration.ofSeconds(10));
        store.init();
    }

    @AfterAll
    static void closeProvider() {
        if (provider != null) provider.close();
    }

    @Test
    void roundTripsMetadataAndSupportsSoftThenHardDelete() {
        String documentId = UUID.randomUUID().toString();
        SkillKnowledgeChunk chunk = new SkillKnowledgeChunk(
                UUID.randomUUID().toString(), "travel", documentId, 0,
                "酒店上限 500 元", "hash-1", "travel.md",
                "差旅制度 > 酒店", 3, "1.0", true);

        store.upsertAll(List.of(new SkillKnowledgeVector(chunk, new float[]{1f, 0f, 0f})));

        List<SkillKnowledgeSearchResult> found = store.search(
                new float[]{1f, 0f, 0f}, Set.of("travel"), 5, 0.1f);
        assertEquals(1, found.size());
        assertEquals("travel.md", found.get(0).source());
        assertEquals("差旅制度 > 酒店", found.get(0).heading());
        assertEquals(3, found.get(0).pageNumber());
        assertEquals("1.0", found.get(0).version());
        assertEquals("hash-1", found.get(0).contentHash());

        assertEquals(1, store.softDeleteByDocument("travel", documentId));
        assertTrue(store.search(new float[]{1f, 0f, 0f}, Set.of("travel"), 5, 0.1f).isEmpty());
        assertEquals(1, store.countByDocument("travel", documentId));

        assertEquals(1, store.hardDeleteByDocument("travel", documentId));
        assertEquals(0, store.countByDocument("travel", documentId));
        assertFalse(store.status("travel").message().isBlank());
    }
}
