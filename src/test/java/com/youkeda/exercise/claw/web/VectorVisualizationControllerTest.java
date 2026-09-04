package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.skill.SemanticTriggerPolicy;
import com.youkeda.exercise.claw.agent.skill.TriggerProperties;
import com.youkeda.exercise.claw.ai.llm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class VectorVisualizationControllerTest {
    private SkillDefinition skill(String name) {
        return new SkillDefinition(name, name + " description", 0, Set.of(), Set.of(), Set.of(),
                null, null, null, null, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparesSkillsIndividuallyWithOneQueryEmbeddingAndRealExampleCounts() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(skill("weather"), skill("transport"), skill("common")));
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.getDimensions()).thenReturn(2);
        when(embeddings.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{1, 0}), List.of(new float[]{0, 1}));
        when(embeddings.embed("rain?")).thenReturn(new float[]{1, 0});
        SemanticTriggerPolicy policy = new SemanticTriggerPolicy(embeddings, registry, new TriggerProperties());
        policy.initializeAfterSkillsReady();
        VectorVisualizationController controller = new VectorVisualizationController(registry, policy);

        Map<String, Object> result = controller.calculateSimilarity("rain?").getBody();
        List<Map<String, Object>> scores = (List<Map<String, Object>>) result.get("similarities");
        assertEquals("weather", scores.get(0).get("skillName"));
        assertEquals(1.0, scores.get(0).get("confidence"));
        assertEquals("transport", scores.get(1).get("skillName"));
        assertEquals(0.0, scores.get(1).get("confidence"));
        assertNull(scores.get(2).get("confidence"));
        verify(embeddings, times(1)).embed("rain?");

        List<Map<String, Object>> points = (List<Map<String, Object>>) controller.getEmbeddings().getBody().get("points");
        Map<String, Object> weather = points.stream().filter(point -> point.get("skillName").equals("weather")).findFirst().orElseThrow();
        assertEquals(1, weather.get("exampleCount"));
        assertEquals(true, weather.get("embeddingReady"));
        assertEquals(0, points.get(0).get("exampleCount"));
        assertEquals(false, points.get(0).get("embeddingReady"));
    }

    @Test
    void missingCacheAndZeroQueryReturnUnavailableInsteadOfFabricatedScores() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(skill("weather")));
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.getDimensions()).thenReturn(2);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(new float[]{0, 0}));
        SemanticTriggerPolicy policy = new SemanticTriggerPolicy(embeddings, registry, new TriggerProperties());
        policy.initializeAfterSkillsReady();
        VectorVisualizationController controller = new VectorVisualizationController(registry, policy);
        assertFalse(policy.hasSkillEmbedding("weather"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> controller.calculateSimilarity("rain?")).getStatusCode());
        verify(embeddings, never()).embed(anyString());

        when(embeddings.embedBatch(anyList())).thenReturn(List.of(new float[]{1, 0}));
        policy.refreshAllEmbeddings();
        when(embeddings.embed("rain?")).thenReturn(new float[]{0, 0});
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> controller.calculateSimilarity("rain?")).getStatusCode());
    }

    @Test
    void waitsForSkillRegistrationBeforeBuildingCache() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of());
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        SemanticTriggerPolicy policy = new SemanticTriggerPolicy(embeddings, registry, new TriggerProperties());
        verifyNoInteractions(embeddings);
        when(registry.getAll()).thenReturn(List.of(skill("weather")));
        when(embeddings.getDimensions()).thenReturn(2);
        when(embeddings.embedBatch(anyList())).thenReturn(List.of(new float[]{1, 0}));
        policy.initializeAfterSkillsReady();
        assertTrue(policy.hasSkillEmbedding("weather"));
        assertEquals(1, policy.getExampleCount("weather"));
    }

    @Test
    void rejectsInvalidInputBeforeCallingEmbeddingService() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SemanticTriggerPolicy policy = mock(SemanticTriggerPolicy.class);
        VectorVisualizationController controller = new VectorVisualizationController(registry, policy);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> controller.calculateSimilarity("  ")).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> controller.calculateSimilarity("x".repeat(4001)));
        assertThrows(ResponseStatusException.class, () -> controller.calculateBatchSimilarity(List.of()));
        assertThrows(ResponseStatusException.class, () -> controller.calculateBatchSimilarity(List.of("valid", " ")));
        verifyNoInteractions(policy);
    }

    @Test
    void noSkillCrossingThresholdHasNoTopMatch() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getAll()).thenReturn(List.of(skill("weather")));
        SemanticTriggerPolicy policy = mock(SemanticTriggerPolicy.class);
        when(policy.similarities("hello")).thenReturn(Map.of("weather", 0.2));
        VectorVisualizationController controller = new VectorVisualizationController(registry, policy);
        assertNull(controller.calculateSimilarity("hello").getBody().get("topMatch"));
    }
}
