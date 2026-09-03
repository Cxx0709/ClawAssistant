package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResultStatusParserTest {

    private final ToolResultStatusParser parser =
            new ToolResultStatusParser(new ObjectMapper());

    @Test
    void treatsStartedBackgroundWorkflowAsSuccessfulToolExecution() {
        assertEquals(ResultStatus.SUCCESS,
                parser.parse("{\"status\":\"started\",\"taskId\":\"task-1\"}"));
    }

    @Test
    void treatsAllCollectedAsSuccessfulToolExecution() {
        assertEquals(ResultStatus.SUCCESS,
                parser.parse("{\"status\":\"ALL_COLLECTED\",\"message\":\"需求信息已齐全。\"}"));
    }

    @Test
    void treatsLowercaseAllCollectedAsSuccessful() {
        assertEquals(ResultStatus.SUCCESS,
                parser.parse("{\"status\":\"all_collected\",\"message\":\"需求信息已齐全。\"}"));
    }

    @Test
    void treatsTravelStateTransitionsAsSuccessful() {
        for (String status : new String[]{
                "OPTIONS_SAVED", "OPTION_SELECTED", "OPTION_COMBINED",
                "OPTION_REVISED", "REVISION_RECORDED", "BUDGET_DECISION_ACCEPT_OVERRUN"}) {
            assertEquals(ResultStatus.SUCCESS,
                    parser.parse("{\"status\":\"" + status + "\"}"), status);
        }
    }

    @Test
    void keepsPartialStatus() {
        assertEquals(ResultStatus.PARTIAL,
                parser.parse("{\"status\":\"PARTIAL\",\"knownSubtotalMin\":100}"));
    }

    @Test
    void mapsBlockedStatus() {
        assertEquals(ResultStatus.BLOCKED,
                parser.parse("{\"status\":\"BLOCKED\",\"reason\":\"安全策略阻止\"}"));
    }

    @Test
    void mapsNeedMoreInformationToPartial() {
        assertEquals(ResultStatus.PARTIAL,
                parser.parse("{\"status\":\"NEED_MORE_INFORMATION\",\"missingFields\":[\"budget\"]}"));
    }

    @Test
    void mapsErrorToFailed() {
        assertEquals(ResultStatus.FAILED,
                parser.parse("{\"error\":\"工具执行异常\"}"));
    }

    @Test
    void mapsEmptyOrNullToFailed() {
        assertEquals(ResultStatus.FAILED, parser.parse(""));
        assertEquals(ResultStatus.FAILED, parser.parse(null));
    }
}
