package com.youkeda.exercise.claw.feature.scout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoutWorkflowPayloadTest {

    @Test
    void preservesExplicitQueryAndBothKnowledgeScopes() {
        ScoutExecutionContext context = new ScoutExecutionContext(
                "AI Agent", "规划知识", "判定知识");

        ScoutExecutionContext decoded = ScoutWorkflowPayload.decode(
                ScoutWorkflowPayload.encode(context));

        assertEquals(context, decoded);
    }

    @Test
    void treatsLegacyPlainPayloadAsExplicitQuery() {
        ScoutExecutionContext decoded = ScoutWorkflowPayload.decode("legacy query");

        assertEquals("legacy query", decoded.explicitQuery());
        assertEquals("", decoded.planningKnowledge());
        assertEquals("", decoded.decisionKnowledge());
    }
}
