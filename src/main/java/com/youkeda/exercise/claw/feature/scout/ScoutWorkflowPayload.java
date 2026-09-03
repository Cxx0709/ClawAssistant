package com.youkeda.exercise.claw.feature.scout;

import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON codec that keeps WorkflowRequest's generic String payload backward compatible. */
public final class ScoutWorkflowPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScoutWorkflowPayload() {
    }

    public static String encode(ScoutExecutionContext context) {
        try {
            return MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to encode Scout workflow payload", e);
        }
    }

    public static ScoutExecutionContext decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return ScoutExecutionContext.withoutKnowledge("");
        }
        String trimmed = payload.trim();
        if (!trimmed.startsWith("{")) {
            return ScoutExecutionContext.withoutKnowledge(payload);
        }
        try {
            return MAPPER.readValue(trimmed, ScoutExecutionContext.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Scout workflow payload", e);
        }
    }
}
