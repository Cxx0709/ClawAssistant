package com.youkeda.exercise.claw.tool.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.scout.feedback.ScoutFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ScoutFeedbackToolTest {

    private ObjectMapper objectMapper;
    private ScoutFeedbackRepository repository;
    private ScoutFeedbackTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        repository = new ScoutFeedbackRepository(new JdbcTemplate(dataSource));
        tool = new ScoutFeedbackTool(objectMapper, new ToolRegistry(), repository);
        tool.init();
    }

    @Test
    void recordsPositiveFeedback() throws Exception {
        String result = tool.execute("""
                {"rating":"USEFUL","topic":"AI Agent","title":"LangChain新版本发布"}
                """, context());

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals("USEFUL", json.path("rating").asText());

        assertEquals(1, repository.findRecent(10).size());
        assertEquals("USEFUL", repository.findRecent(10).get(0).rating());
    }

    @Test
    void recordsNegativeFeedback() throws Exception {
        String result = tool.execute("""
                {"rating":"NOT_USEFUL","topic":"前端教程","reason":"和我无关"}
                """, context());

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());

        assertEquals(1, repository.findNegativeTopics().size());
        assertTrue(repository.findNegativeTopics().contains("前端教程"));
    }

    @Test
    void rejectsInvalidRating() throws Exception {
        String result = tool.execute("""
                {"rating":"MAYBE"}
                """, context());

        JsonNode json = objectMapper.readTree(result);
        assertEquals("ERROR", json.path("status").asText());
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext("", null, "u");
    }
}
