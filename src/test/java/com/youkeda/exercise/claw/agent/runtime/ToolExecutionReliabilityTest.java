package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 3：Agent Tool Calling Reliability 测试。
 *
 * <p>验证：异常消费化、累计调用限制、ToolResult 格式一致性、多工具连续调用。
 */
class ToolExecutionReliabilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final SafetyPolicy safetyPolicy = mock(SafetyPolicy.class);
    private final SkillPendingCoordinator pendingCoordinator = mock(SkillPendingCoordinator.class);
    private final AgentActivityRecorder activityRecorder = mock(AgentActivityRecorder.class);
    private final ToolResultStatusParser statusParser = mock(ToolResultStatusParser.class);
    private final ToolExecutor executor = new ToolExecutor(
            registry, safetyPolicy, pendingCoordinator,
            mock(PendingToolCoordinator.class),
            activityRecorder, statusParser, mock(com.youkeda.exercise.claw.agent.plan.PlanStore.class),
            objectMapper);

    // ==================== 1. 异常消费化：Tool 抛异常不应穿透 Agent Loop ====================

    @Test
    void toolExecutionExceptionShouldNotCrashAgent() {
        // 注册一个会抛 RuntimeException 的工具
        Tool throwingTool = mock(Tool.class);
        when(throwingTool.getName()).thenReturn("unstable_tool");
        when(throwingTool.isAvailable(any())).thenReturn(true);
        when(throwingTool.execute(anyString(), any()))
                .thenThrow(new RuntimeException("网络超时"));
        when(registry.find("unstable_tool")).thenReturn(throwingTool);

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "unstable_tool", "{}")),
                new ToolExecutionContext("查询", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "查询",
                new HashSet<>());

        // 不应抛出异常
        assertEquals(1, batch.results().size());
        String result = batch.results().get(0);
        assertNotNull(result, "异常时应返回 ToolResult JSON 而非 null");

        // 格式检查
        assertTrue(result.contains("\"status\":\"ERROR\""),
                "异常 ToolResult 应包含 status=ERROR, 实际: " + result);
        assertTrue(result.contains("\"errorCode\":\"TOOL_EXECUTION_FAILED\""),
                "异常 ToolResult 应包含 errorCode=TOOL_EXECUTION_FAILED, 实际: " + result);
        assertTrue(result.contains("\"fallback_required\":true"),
                "异常 ToolResult 应包含 fallback_required=true, 实际: " + result);
        assertTrue(result.contains("\"message\""),
                "异常 ToolResult 应包含 message 字段, 实际: " + result);

        // 活动记录应标记为失败
        verify(activityRecorder).toolFinished(
                eq("req-1"), eq("common"), eq("unstable_tool"), eq(false), anyLong());
    }

    // ==================== 2. Tool 返回 null 是合法的（工具自行处理） ====================

    @Test
    void toolReturningNullIsAllowedResult() {
        Tool nullTool = mock(Tool.class);
        when(nullTool.getName()).thenReturn("null_tool");
        when(nullTool.isAvailable(any())).thenReturn(true);
        when(nullTool.execute(anyString(), any())).thenReturn(null);
        when(registry.find("null_tool")).thenReturn(nullTool);

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "null_tool", "{}")),
                new ToolExecutionContext("查询", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "查询",
                new HashSet<>());

        // 当前行为：execute 返回 null 不抛异常 → result 为 null。
        // 这是已知边界：工具若返回 null 应自行记录 warning 日志，由框架打 error log
        // 但不在此 Phase 改造（所有 Tool 已确认不会返回 null）。
        assertEquals(1, batch.toolCallCount(), "null 返回值仍计入 toolCallCount");
    }

    // ==================== 3. ToolResult 格式一致性 ====================

    @Test
    void toolResultFormatShouldBeStableAcrossStatuses() {
        // 成功
        Tool successTool = mock(Tool.class);
        when(successTool.getName()).thenReturn("success_tool");
        when(successTool.isAvailable(any())).thenReturn(true);
        when(successTool.execute(anyString(), any()))
                .thenReturn("{\"status\":\"SUCCESS\",\"data\":{}}");
        when(registry.find("success_tool")).thenReturn(successTool);

        ToolExecutor.ToolExecutionBatch successBatch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "success_tool", "{}")),
                new ToolExecutionContext("查询", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "查询",
                new HashSet<>());
        String successResult = successBatch.results().get(0);
        assertTrue(successResult.contains("\"status\":\"SUCCESS\""),
                "成功结果应包含 status=SUCCESS");

        // 被 blocked（安全策略）
        when(safetyPolicy.canExecute(eq("blocked_tool"), anyString()))
                .thenReturn("安全策略禁止");
        Tool blockedTool = mock(Tool.class);
        when(blockedTool.getName()).thenReturn("blocked_tool");
        when(registry.find("blocked_tool")).thenReturn(blockedTool);

        ToolExecutor.ToolExecutionBatch blockedBatch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "blocked_tool", "{}")),
                new ToolExecutionContext("查询", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "查询",
                new HashSet<>());
        String blockedResult = blockedBatch.results().get(0);
        assertTrue(blockedResult.contains("\"status\":\"BLOCKED\""),
                "被 blocked 结果应包含 status=BLOCKED");

        // 异常
        Tool throwingTool = mock(Tool.class);
        when(throwingTool.getName()).thenReturn("throwing_tool");
        when(throwingTool.isAvailable(any())).thenReturn(true);
        when(throwingTool.execute(anyString(), any()))
                .thenThrow(new RuntimeException("internal error"));
        when(registry.find("throwing_tool")).thenReturn(throwingTool);

        ToolExecutor.ToolExecutionBatch errorBatch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "throwing_tool", "{}")),
                new ToolExecutionContext("查询", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "查询",
                new HashSet<>());
        String errorResult = errorBatch.results().get(0);
        assertTrue(errorResult.contains("\"status\":\"ERROR\""),
                "异常结果应包含 status=ERROR");

        // 三种状态 JSON 均可被 ObjectMapper 解析
        assertDoesNotThrow(() -> objectMapper.readTree(successResult));
        assertDoesNotThrow(() -> objectMapper.readTree(blockedResult));
        assertDoesNotThrow(() -> objectMapper.readTree(errorResult));
    }

    // ==================== 4. toErrorResult 静态方法 ====================

    @Test
    void toErrorResultProducesValidJson() {
        String result = ToolExecutor.toErrorResult("test_tool",
                new RuntimeException("连接被拒绝"));
        assertNotNull(result);
        assertTrue(result.contains("TOOL_EXECUTION_FAILED"));
        assertTrue(result.contains("test_tool"));
        assertTrue(result.contains("连接被拒绝"));

        // must be valid JSON
        assertDoesNotThrow(() -> objectMapper.readTree(result));
    }

    // ==================== 5. 多 Tool 连续调用不丢上下文 ====================

    @Test
    void multiToolCallSequenceShouldKeepContext() {
        SkillSession session = SkillSession.create("u1").withActiveSkill("travel");

        // 注册 3 个不同领域的工具
        stubTool("travel_collect", "{\"status\":\"SUCCESS\",\"data\":{\"plan\":\"3日游\"}}");
        stubTool("file_generate", "{\"status\":\"SUCCESS\",\"data\":{\"file\":\"plan.pdf\"}}");
        stubTool("create_schedule_task", "{\"status\":\"SUCCESS\",\"data\":{\"taskId\":\"t1\"}}");

        // 第 1 轮：travel
        SkillSession s1 = session;
        ToolExecutor.ToolExecutionBatch b1 = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "travel_collect", "{}")),
                new ToolExecutionContext("规划旅游", s1, "u1"),
                s1, null, "req-1", "travel", "规划旅游",
                new HashSet<>());

        SkillSession s2 = b1.session();
        assertEquals(1, b1.toolCallCount());
        assertTrue(b1.results().get(0).contains("3日游"));

        // 第 2 轮：file_generate（用上一轮更新后的 session）
        ToolExecutor.ToolExecutionBatch b2 = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc2", "file_generate", "{}")),
                new ToolExecutionContext("生成PDF", s2, "u1"),
                s2, null, "req-1", "travel", "生成PDF",
                new HashSet<>());

        SkillSession s3 = b2.session();
        assertEquals(1, b2.toolCallCount());
        assertTrue(b2.results().get(0).contains("plan.pdf"));

        // 第 3 轮：schedule
        ToolExecutor.ToolExecutionBatch b3 = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc3", "create_schedule_task", "{}")),
                new ToolExecutionContext("设置提醒", s3, "u1"),
                s3, null, "req-1", "travel", "设置提醒",
                new HashSet<>());

        assertEquals(1, b3.toolCallCount());
        assertTrue(b3.results().get(0).contains("t1"));
    }

    // ==================== 6. 去重不应影响计数 ====================

    @Test
    void duplicateToolCallsShouldNotIncrementCount() {
        stubTool("weather_query", "{\"status\":\"SUCCESS\"}");
        SkillSession session = SkillSession.create("u1");
        Set<String> executed = new HashSet<>();

        // 第一次调用
        ToolExecutor.ToolExecutionBatch b1 = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "weather_query", "{\"city\":\"北京\"}")),
                new ToolExecutionContext("查天气", session, "u1"),
                session, null, "req-1", "weather", "查天气",
                executed);

        assertEquals(1, b1.toolCallCount());

        // 重复调用（相同 name + args）
        ToolExecutor.ToolExecutionBatch b2 = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc2", "weather_query", "{\"city\":\"北京\"}")),
                new ToolExecutionContext("查天气", session, "u1"),
                session, null, "req-1", "weather", "查天气",
                executed);

        assertEquals(0, b2.toolCallCount(),
                "重复调用不应增加计数");
        assertTrue(b2.results().get(0).contains("BLOCKED"),
                "重复调用应返回 BLOCKED");
    }

    // ==================== helpers ====================

    private void stubTool(String name, String result) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.isAvailable(any())).thenReturn(true);
        when(tool.execute(anyString(), any())).thenReturn(result);
        when(registry.find(name)).thenReturn(tool);
        when(statusParser.parse(anyString()))
                .thenReturn(com.youkeda.exercise.claw.agent.model.ResultStatus.SUCCESS);
    }
}
