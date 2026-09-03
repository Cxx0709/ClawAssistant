package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillKnowledgeImportServiceTest {

    @Mock
    private SkillKnowledgeStore store;
    @Mock
    private EmbeddingClient embeddingClient;

    @Test
    void importsAllChunksWithOneBatchEmbeddingAndOneStoreWrite() {
        DocumentChunker chunker = new DocumentChunker();
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, chunker);
        String markdown = "# 制度\n介绍\n## 酒店\n上限 500 元";
        when(embeddingClient.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) vectors.add(new float[]{1f, i + 1f});
            return vectors;
        });
        when(store.setDocumentEnabled(org.mockito.ArgumentMatchers.eq("travel"),
                anyString(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(2L);

        KnowledgeImportResult result = service.importDocument(
                "travel", markdown, "travel.md", "markdown", "1.0");

        assertEquals("IMPORTED", result.status());
        assertEquals(2, result.totalChunks());
        assertEquals(2, result.successCount());
        verify(embeddingClient).embedBatch(anyList());
        ArgumentCaptor<List<SkillKnowledgeVector>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).upsertAll(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertTrue(captor.getValue().stream().allMatch(point -> !point.chunk().enabled()));
        verify(store).setDocumentEnabled("travel", result.documentId(), true);
        assertEquals("制度 > 酒店", captor.getValue().get(1).chunk().heading());
        assertTrue(captor.getValue().stream()
                .allMatch(v -> v.chunk().contentHash() != null && !v.chunk().contentHash().isBlank()));
    }

    @Test
    void statusReportsConfigurationCircuitBackendAndActivePoints() {
        SkillRegistry registry = org.mockito.Mockito.mock(SkillRegistry.class);
        SkillDefinition definition = org.mockito.Mockito.mock(SkillDefinition.class);
        when(definition.knowledge()).thenReturn(new SkillKnowledgeConfig(true, 5, 0.5f, 12000));
        when(registry.find("travel")).thenReturn(java.util.Optional.of(definition));
        SkillsProperties properties = new SkillsProperties();
        properties.getKnowledge().setGlobalEnabled(true);
        when(embeddingClient.circuitStateName()).thenReturn("OPEN");
        when(store.status("travel"))
                .thenReturn(new KnowledgeStoreStatus(true, "skill_knowledge", 4, "OK"));
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, new DocumentChunker(), registry, properties);

        KnowledgeStoreStatus status = service.status("travel");

        assertTrue(status.globalEnabled());
        assertTrue(status.skillKnowledgeEnabled());
        assertEquals("OPEN", status.embeddingCircuitState());
        assertEquals(4, status.pointCount());
    }

    @Test
    void autoDetectsMarkdownAndRejectsDocumentsOverTheChunkLimit() {
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, new DocumentChunker());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxChunks", 1);

        assertThrows(IllegalArgumentException.class, () -> service.importDocument(
                "travel", "# 第一章\n正文\n## 第二章\n正文", "RULES.MD", "auto", "1.0"));

        org.mockito.Mockito.verifyNoInteractions(embeddingClient);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void rejectsUnknownSkillBeforeEmbeddingOrWriting() {
        SkillRegistry registry = org.mockito.Mockito.mock(SkillRegistry.class);
        when(registry.find("unknown")).thenReturn(java.util.Optional.empty());
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, new DocumentChunker(), registry);

        assertThrows(IllegalArgumentException.class, () -> service.importDocument(
                "unknown", "有效文本", "manual", "text", "1.0"));

        org.mockito.Mockito.verifyNoInteractions(embeddingClient);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void embeddingFailureDoesNotWritePartialDocument() {
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, new DocumentChunker());
        when(embeddingClient.embedBatch(anyList())).thenThrow(new IllegalStateException("offline"));

        assertThrows(SkillKnowledgeImportException.class, () -> service.importDocument(
                "travel", "有效文本", "manual", "text", "1.0"));

        verify(store, never()).upsertAll(anyList());
    }

    @Test
    void storeFailureAttemptsDocumentScopedCleanup() {
        SkillKnowledgeImportService service = new SkillKnowledgeImportService(
                store, embeddingClient, new DocumentChunker());
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(new float[]{1f}));
        org.mockito.Mockito.doThrow(new SkillKnowledgeStoreException("down", new RuntimeException()))
                .when(store).upsertAll(anyList());

        assertThrows(SkillKnowledgeImportException.class, () -> service.importDocument(
                "travel", "有效文本", "manual", "text", "1.0"));

        verify(store).hardDeleteByDocument(org.mockito.ArgumentMatchers.eq("travel"), anyString());
    }
}
