package com.youkeda.exercise.claw.feature.scout;

/** Immutable context carried from Scout submission to the LLM planning and decision stages. */
public record ScoutExecutionContext(
        String explicitQuery,
        String planningKnowledge,
        String decisionKnowledge
) {
    public ScoutExecutionContext {
        explicitQuery = normalize(explicitQuery);
        planningKnowledge = normalize(planningKnowledge);
        decisionKnowledge = normalize(decisionKnowledge);
    }

    public static ScoutExecutionContext withoutKnowledge(String explicitQuery) {
        return new ScoutExecutionContext(explicitQuery, "", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
