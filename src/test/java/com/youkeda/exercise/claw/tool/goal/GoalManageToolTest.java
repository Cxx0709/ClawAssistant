package com.youkeda.exercise.claw.tool.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.goal.GrowthGoal;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalRepository;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalManageToolTest {

    private ObjectMapper objectMapper;
    private GrowthGoalRepository repository;
    private GoalManageTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        repository = new GrowthGoalRepository(new JdbcTemplate(dataSource));
        GrowthGoalService service = new GrowthGoalService(repository);
        tool = new GoalManageTool(objectMapper, new ToolRegistry(), service);
        tool.init();
    }

    @Test
    void createPersistsGoalForCurrentUser() throws Exception {
        String result = tool.execute("""
                {"action":"create","title":"完成大模型比赛作品","success_criteria":"提交可演示的参赛版本","deadline":"2026-10-01"}
                """, context("user-a"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());

        List<GrowthGoal> goals = repository.findByUser("user-a", null);
        assertEquals(1, goals.size());
        assertEquals("完成大模型比赛作品", goals.get(0).title());
        assertEquals("2026-10-01", goals.get(0).deadline());
        assertEquals(GrowthGoal.Status.ACTIVE, goals.get(0).status());
        assertEquals(0, repository.findByUser("user-b", null).size());
    }

    @Test
    void listReturnsAllGoalsForUser() throws Exception {
        repository.create("u", "考研", "考上研究生", "2026-12-31");
        repository.create("u", "比赛", "提交参赛作品", "2026-10-01");

        String result = tool.execute("""
                {"action":"list"}
                """, context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals(2, json.path("count").asInt());
        assertEquals(2, json.path("goals").size());
    }

    @Test
    void listWithStatusFilter() throws Exception {
        GrowthGoal active = repository.create("u", "考研", "", null);
        repository.create("u", "旧目标", "", null);
        repository.cancel("u", active.id());

        String result = tool.execute("""
                {"action":"list","status":"ACTIVE"}
                """, context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals(1, json.path("count").asInt());
    }

    @Test
    void updateProgressAndEvidence() throws Exception {
        GrowthGoal goal = repository.create("u", "比赛", "提交作品", "2026-10-01");

        String result = tool.execute("""
                {"action":"update","goal_id":"%d","progress":50,"evidence":"完成了核心功能"}
                """.formatted(goal.id()), context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals(50, json.path("goal").path("progress").asInt());
    }

    @Test
    void completeSetsStatusToCompleted() throws Exception {
        GrowthGoal goal = repository.create("u", "比赛", "提交作品", "2026-10-01");

        String result = tool.execute("""
                {"action":"complete","goal_id":"%d"}
                """.formatted(goal.id()), context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals("COMPLETED", json.path("goal").path("status").asText());
        assertEquals(100, json.path("goal").path("progress").asInt());
    }

    @Test
    void cancelMarksGoalCancelled() throws Exception {
        GrowthGoal goal = repository.create("u", "比赛", "提交作品", "2026-10-01");

        String result = tool.execute("""
                {"action":"cancel","goal_id":"%d"}
                """.formatted(goal.id()), context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("SUCCESS", json.path("status").asText());
        assertTrue(json.path("cancelled").asBoolean());
    }

    @Test
    void updateNonexistentGoalReturnsError() throws Exception {
        String result = tool.execute("""
                {"action":"update","goal_id":"999","progress":50}
                """, context("u"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals("ERROR", json.path("status").asText());
    }

    private ToolExecutionContext context(String userId) {
        return new ToolExecutionContext("", null, userId);
    }
}
