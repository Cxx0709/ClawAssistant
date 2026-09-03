package com.youkeda.exercise.claw.agent.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentActivityStoreTest {

    private AgentActivityStore store;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        store = new AgentActivityStore(new JdbcTemplate(dataSource),
                new com.youkeda.exercise.claw.identity.UserExecutionContext());
        store.init();
    }

    @Test
    void recordsAndReturnsNewestActivitiesFirst() {
        store.record(new AgentActivityEvent(
                "request-1", ActivityEventType.REQUEST_RECEIVED,
                null, null, "RUNNING", "查询杭州天气", null));
        store.record(new AgentActivityEvent(
                "request-1", ActivityEventType.SKILL_SELECTED,
                "weather", null, "SUCCESS", "选择 weather Skill", null));
        store.record(new AgentActivityEvent(
                "request-1", ActivityEventType.TOOL_SUCCEEDED,
                "weather", "weather_query", "SUCCESS", "工具执行成功", 126L));

        List<AgentActivity> activities = store.findRecent(20);

        assertEquals(3, activities.size());
        assertEquals(ActivityEventType.TOOL_SUCCEEDED, activities.get(0).eventType());
        assertEquals("weather_query", activities.get(0).toolName());
        assertEquals(126L, activities.get(0).durationMs());
        assertNotNull(activities.get(0).createdAt());
    }

    @Test
    void calculatesDashboardSummary() {
        store.record(new AgentActivityEvent(
                "request-1", ActivityEventType.REQUEST_RECEIVED,
                null, null, "RUNNING", "请求一", null));
        store.record(new AgentActivityEvent(
                "request-1", ActivityEventType.TOOL_SUCCEEDED,
                "weather", "weather_query", "SUCCESS", "成功", 20L));
        store.record(new AgentActivityEvent(
                "request-2", ActivityEventType.REQUEST_RECEIVED,
                null, null, "RUNNING", "请求二", null));
        store.record(new AgentActivityEvent(
                "request-2", ActivityEventType.TOOL_FAILED,
                "travel", "route_query", "FAILED", "失败", 35L));

        DashboardSummary summary = store.getSummary();

        assertEquals(2, summary.requestCount());
        assertEquals(2, summary.toolCallCount());
        assertEquals(1, summary.failureCount());
        assertNotNull(summary.lastActivityAt());
    }
}
