package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoutKnowledgeProviderTest {

    @Test
    void recallsSeparateKnowledgeForPlanningAndDecision() {
        SkillKnowledgeService service = mock(SkillKnowledgeService.class);
        when(service.recall("AI Agent 搜索规划、领域术语和可信信息源规则", "information-scout"))
                .thenReturn("planning");
        when(service.recall("AI Agent 高价值信息判断、筛选和行动优先级标准", "information-scout"))
                .thenReturn("decision");
        ScoutKnowledgeProvider provider = new ScoutKnowledgeProvider(service);

        ScoutExecutionContext context = provider.forExplicitQuery("AI Agent");

        assertEquals("AI Agent", context.explicitQuery());
        assertEquals("planning", context.planningKnowledge());
        assertEquals("decision", context.decisionKnowledge());
        verify(service).recall("AI Agent 搜索规划、领域术语和可信信息源规则", "information-scout");
        verify(service).recall("AI Agent 高价值信息判断、筛选和行动优先级标准", "information-scout");
    }
}
