package com.youkeda.exercise.claw.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.model.EvaluationState;
import com.youkeda.exercise.claw.agent.model.ExecutionStatus;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 2 PlanState 落库测试：save→get 往返、clear、空表返回 null。
 */
class SqlitePlanStoreTest {

    private SqlitePlanStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE agent_plans (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                plan_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);
        store = new SqlitePlanStore(jdbc, new ObjectMapper());
    }

    private PlanState samplePlan() {
        PlanState plan = new PlanState("帮我规划旅游", List.of(
                new PlanTask("t1", "查天气", List.of()),
                new PlanTask("t2", "订酒店", List.of("t1"))));
        plan.setVersion(3);
        return plan;
    }

    @Test
    void emptyTableReturnsNull() {
        assertNull(store.get());
    }

    @Test
    void saveThenGetRoundTrips() {
        PlanState plan = samplePlan();
        store.save(plan);

        // 先确认行真实写入
        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_plans", Integer.class);
        assertEquals(1, rowCount, "save 后应有 1 行");

        PlanState loaded = store.get();
        assertNotNull(loaded);
        assertEquals("帮我规划旅游", loaded.getGoal());
        assertEquals(3, loaded.getVersion());
        assertEquals(2, loaded.getTasks().size());
        // 任务状态完整
        PlanTask t2 = loaded.findTask("t2");
        assertEquals("订酒店", t2.getDescription());
        assertEquals(List.of("t1"), t2.getDependencies());
        assertEquals(ExecutionStatus.PENDING, t2.getExecutionStatus());
        assertEquals(EvaluationState.UNEVALUATED, t2.getEvaluationState());
    }

    @Test
    void saveUpdatesExistingRow() {
        PlanState plan = samplePlan();
        store.save(plan);
        store.save(new PlanState("改后的目标", List.of()));

        PlanState loaded = store.get();
        assertEquals("改后的目标", loaded.getGoal());
        // 单行：仍是 id=1
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_plans", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void clearRemovesRow() {
        store.save(samplePlan());
        store.clear();
        assertNull(store.get());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_plans", Integer.class);
        assertEquals(0, count);
    }
}
