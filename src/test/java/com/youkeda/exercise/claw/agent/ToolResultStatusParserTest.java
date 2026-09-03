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
}
