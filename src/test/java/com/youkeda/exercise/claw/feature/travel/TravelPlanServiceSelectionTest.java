package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TravelPlanServiceSelectionTest {

    @Test
    void acceptsNormalizedDaysWithoutDuplicateDurationText() {
        ObjectMapper objectMapper = new ObjectMapper();
        UserExecutionContext executionContext = new UserExecutionContext();
        TravelPlanService service = new TravelPlanService(
                new DefaultTravelPlanStateStore(), objectMapper, executionContext);

        try (UserExecutionContext.Scope ignored = executionContext.open("user-days")) {
            ObjectNode collect = objectMapper.createObjectNode();
            collect.put("action", "collect");
            collect.put("destination", "杭州");
            collect.put("days", 4);

            ObjectNode result = service.handle(collect);

            assertEquals("NEED_MORE_INFORMATION", result.path("status").asText());
            assertFalse(result.path("missing_fields").toString().contains("\"duration\""));
        }
    }

    @Test
    void savesOptionsAndResolvesUserFacingOptionReferences() {
        ObjectMapper objectMapper = new ObjectMapper();
        UserExecutionContext executionContext = new UserExecutionContext();
        TravelPlanService service = new TravelPlanService(
                new DefaultTravelPlanStateStore(), objectMapper, executionContext);

        try (UserExecutionContext.Scope ignored = executionContext.open("user-1")) {
            ObjectNode save = objectMapper.createObjectNode();
            save.put("action", "save_options");
            save.put("option_count", 3);
            ArrayNode options = save.putArray("options");
            addOption(options, "plan_a", "方案A");
            addOption(options, "plan_b", "方案B - 自驾自由游");
            addOption(options, "plan_c", "方案C");

            assertEquals("OPTIONS_SAVED", service.handle(save).path("status").asText());

            ObjectNode selectByName = objectMapper.createObjectNode();
            selectByName.put("action", "select_option");
            selectByName.put("selected_option_id", "方案B");
            assertEquals("plan_b", service.handle(selectByName)
                    .path("selected_option_id").asText());

            ObjectNode selectByOrdinal = objectMapper.createObjectNode();
            selectByOrdinal.put("action", "select_option");
            selectByOrdinal.put("selected_option_id", "第二个");
            assertEquals("plan_b", service.handle(selectByOrdinal)
                    .path("selected_option_id").asText());

            ObjectNode selectByModelAlias = objectMapper.createObjectNode();
            selectByModelAlias.put("action", "select_option");
            selectByModelAlias.put("option_id", "plan_b");
            assertEquals("plan_b", service.handle(selectByModelAlias)
                    .path("selected_option_id").asText());
        }
    }

    @Test
    void explainsThatSelectionCannotRunBeforeOptionsAreSaved() {
        ObjectMapper objectMapper = new ObjectMapper();
        UserExecutionContext executionContext = new UserExecutionContext();
        TravelPlanService service = new TravelPlanService(
                new DefaultTravelPlanStateStore(), objectMapper, executionContext);

        try (UserExecutionContext.Scope ignored = executionContext.open("user-1")) {
            ObjectNode select = objectMapper.createObjectNode();
            select.put("action", "select_option");
            select.put("selected_option_id", "方案B");
            ObjectNode result = service.handle(select);
            assertEquals("INVALID_ARGUMENT", result.path("status").asText());
            assertEquals("OPTIONS_NOT_SAVED", result.path("error_code").asText());
            assertEquals(false, result.path("retryable").asBoolean());
        }
    }

    private static void addOption(ArrayNode options, String id, String displayName) {
        ObjectNode option = options.addObject();
        option.put("option_id", id);
        option.put("display_name", displayName);
        option.put("positioning", "测试定位");
        option.put("itinerary_summary", "测试行程");
    }
}
