package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillKnowledgeServiceTest {

    @Mock
    private SkillKnowledgeStore store;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private SkillRegistry skillRegistry;

    private SkillsProperties skillsProperties;
    private SkillKnowledgeService service;

    @BeforeEach
    void setUp() {
        skillsProperties = new SkillsProperties();
        skillsProperties.getKnowledge().setGlobalEnabled(true);
        service = new SkillKnowledgeService(store, embeddingClient, skillRegistry,
                skillsProperties, new KnowledgePromptFormatter());
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "minScore", 0.5f);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 3);
        ReflectionTestUtils.setField(service, "maxContextChars", 12000);
    }

    @Test
    void globalSwitchDisablesRecallBeforeEmbedding() {
        skillsProperties.getKnowledge().setGlobalEnabled(false);

        assertEquals("", service.recall("三亚旅行", "travel"));
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void keepsAtMostTwoChunksFromSameDocument() {
        enableSkill(new SkillKnowledgeConfig(true, 5, 0.5f, 12000));
        when(embeddingClient.embed("三亚旅行")).thenReturn(new float[]{1f, 0f});
        when(store.search(any(), eq(Set.of("travel")), anyInt(), anyFloat())).thenReturn(List.of(
                result("c1", "doc-1", "第一条", 0.95),
                result("c2", "doc-1", "第二条", 0.90),
                result("c3", "doc-1", "第三条", 0.85),
                result("c4", "doc-2", "其他文档", 0.80)
        ));

        String prompt = service.recall("三亚旅行", "travel");

        assertTrue(prompt.contains("第一条"));
        assertTrue(prompt.contains("第二条"));
        assertFalse(prompt.contains("第三条"));
        assertTrue(prompt.contains("其他文档"));
    }

    @Test
    void oversizedBestCandidateDoesNotDiscardLaterShortCandidate() {
        enableSkill(new SkillKnowledgeConfig(true, 1, 0.5f, 360));
        when(embeddingClient.embed("预算")).thenReturn(new float[]{1f});
        when(store.search(any(), eq(Set.of("travel")), anyInt(), anyFloat())).thenReturn(List.of(
                result("large", "doc-large", "A".repeat(1000), 0.95),
                result("short", "doc-short", "短规则", 0.90)
        ));

        String prompt = service.recall("预算", "travel");

        assertTrue(prompt.contains("短规则"));
        assertTrue(prompt.length() <= 360);
    }

    private void enableSkill(SkillKnowledgeConfig config) {
        SkillDefinition definition = new SkillDefinition(
                "travel", "travel", 1, Set.of(), Set.of(), Set.of(),
                "prompts/skills/travel.txt", null, config, null, true);
        when(skillRegistry.find("travel")).thenReturn(Optional.of(definition));
    }

    private SkillKnowledgeSearchResult result(
            String chunkId, String documentId, String content, double score) {
        return new SkillKnowledgeSearchResult(
                chunkId, "travel", documentId, content,
                "hash-" + chunkId, "guide.md", "费用标准",
                null, "1.0", score);
    }
}
