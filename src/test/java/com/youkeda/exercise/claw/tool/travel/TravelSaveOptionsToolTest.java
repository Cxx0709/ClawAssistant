package com.youkeda.exercise.claw.tool.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.travel.TravelPlanService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelSaveOptionsToolTest {

    @Test
    void routesToSaveOptionsAction() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TravelPlanService service = mock(TravelPlanService.class);
        when(service.handle(any())).thenReturn(
                objectMapper.createObjectNode().put("status", "OPTIONS_SAVED"));
        TravelSaveOptionsTool tool = new TravelSaveOptionsTool(
                service, objectMapper, new ToolRegistry());

        tool.execute("{\"option_count\":1,\"options\":[]}", ToolExecutionContext.EMPTY);

        ArgumentCaptor<JsonNode> arguments = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).handle(arguments.capture());
        assertEquals("save_options", arguments.getValue().path("action").asText());
    }
}
