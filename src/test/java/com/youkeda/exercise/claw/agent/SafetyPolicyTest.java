package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.runtime.Tool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutor;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4：Agent Safety Policy 与 Tool Permission Control 测试。
 */
class SafetyPolicyTest {

    // ==================== 工具风险分级 ====================

    @Test
    void shouldClassifyHighRiskToolsCorrectly() {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals(SafetyPolicy.ToolRiskLevel.HIGH, policy.getRiskLevel("file_delete"));
        assertEquals(SafetyPolicy.ToolRiskLevel.HIGH, policy.getRiskLevel("cancel_schedule_task"));
    }

    @Test
    void shouldClassifyMediumRiskToolsCorrectly() {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals(SafetyPolicy.ToolRiskLevel.MEDIUM, policy.getRiskLevel("file_generate"));
        assertEquals(SafetyPolicy.ToolRiskLevel.MEDIUM, policy.getRiskLevel("image_generate"));
        assertEquals(SafetyPolicy.ToolRiskLevel.MEDIUM, policy.getRiskLevel("anime_subscribe"));
    }

    @Test
    void shouldClassifyLowRiskToolsCorrectly() {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals(SafetyPolicy.ToolRiskLevel.LOW, policy.getRiskLevel("web_search"));
        assertEquals(SafetyPolicy.ToolRiskLevel.LOW, policy.getRiskLevel("travel_collect"));
    }

    @Test
    void unknownToolsShouldBeNone() {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals(SafetyPolicy.ToolRiskLevel.NONE, policy.getRiskLevel("time_query"));
        assertEquals(SafetyPolicy.ToolRiskLevel.NONE, policy.getRiskLevel("weather_query"));
        assertEquals(SafetyPolicy.ToolRiskLevel.NONE, policy.getRiskLevel("unknown_tool"));
    }

    // ==================== 高风险拦截 ====================

    @Test
    void highRiskToolShouldReturnBlockedConfirmRequired() {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals("BLOCKED_CONFIRM_REQUIRED",
                policy.canExecute("file_delete", "{\"file_id\": 1}"));
    }

    @Test
    void mediumRiskToolShouldNotBeBlocked() {
        SafetyPolicy policy = new SafetyPolicy();
        assertNull(policy.canExecute("file_generate", "{}"));
        assertNull(policy.canExecute("image_generate", "{}"));
    }

    @Test
    void lowRiskToolShouldNotBeBlocked() {
        SafetyPolicy policy = new SafetyPolicy();
        assertNull(policy.canExecute("web_search", "{}"));
        assertNull(policy.canExecute("travel_collect", "{}"));
    }

    @Test
    void noneRiskToolShouldNotBeBlocked() {
        SafetyPolicy policy = new SafetyPolicy();
        assertNull(policy.canExecute("time_query", "{}"));
        assertNull(policy.canExecute("weather_query", "{}"));
    }

    // ==================== 黑名单 ====================

    @Test
    void emptyOrNullToolNameShouldBeBlocked() {
        SafetyPolicy policy = new SafetyPolicy();
        assertNotNull(policy.canExecute("", "{}"));
        assertTrue(policy.canExecute("", "{}").contains("空"));
        assertNotNull(policy.canExecute(null, "{}"));
    }

    // ==================== 配置关闭后放行 ====================

    @Test
    void highRiskConfirmationDisabledShouldAllowExecution() {
        SafetyPolicy policy = new SafetyPolicy(false);
        assertNull(policy.canExecute("file_delete", "{}"));
        assertNull(policy.canExecute("didi_ride", "{}"));
    }

    // ==================== 集成测试：ToolExecutor + SafetyPolicy ====================

    @Test
    void highRiskToolShouldBeBlockedAtExecutorLevel() {
        SafetyPolicy policy = new SafetyPolicy();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentActivityRecorder recorder = mock(AgentActivityRecorder.class);

        ToolExecutor executor = new ToolExecutor(
                registry, policy, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                recorder, mock(ToolResultStatusParser.class), mock(PlanStore.class),
                new ObjectMapper(), mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        Tool deleteTool = mock(Tool.class);
        when(deleteTool.getName()).thenReturn("file_delete");
        when(deleteTool.isAvailable(any())).thenReturn(true);
        when(registry.find("file_delete")).thenReturn(deleteTool);

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "file_delete", "{\"file_id\":42}")),
                new ToolExecutionContext("删掉这个文件", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "删掉这个文件",
                new HashSet<>());

        String result = batch.results().get(0);
        assertTrue(result.contains("\"status\":\"BLOCKED\""),
                "高风险工具应在 Executor 层被 BLOCKED, 实际: " + result);
        assertTrue(result.contains("CONFIRM_REQUIRED"),
                "BLOCKED 原因应包含 CONFIRM_REQUIRED, 实际: " + result);
    }

    // ==================== BLOCKED 结果可恢复 ====================

    @Test
    void blockedToolResultShouldBeRecoverable() {
        SafetyPolicy policy = new SafetyPolicy();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentActivityRecorder recorder = mock(AgentActivityRecorder.class);

        ToolExecutor executor = new ToolExecutor(
                registry, policy, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                recorder, mock(ToolResultStatusParser.class), mock(PlanStore.class),
                new ObjectMapper(), mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        Tool deleteTool = mock(Tool.class);
        when(deleteTool.getName()).thenReturn("file_delete");
        when(registry.find("file_delete")).thenReturn(deleteTool);

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "file_delete", "{}")),
                new ToolExecutionContext("删除文件", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "删除文件",
                new HashSet<>());

        String blockedResult = batch.results().get(0);
        assertDoesNotThrow(() -> new ObjectMapper().readTree(blockedResult));
        assertEquals(0, batch.toolCallCount(),
                "高风险工具被拦截不应计入 toolCallCount");
        assertFalse(batch.executedInBatch());
    }

    // ==================== 低风险工具直达 ====================

    @Test
    void lowRiskToolShouldExecuteDirectly() {
        SafetyPolicy policy = new SafetyPolicy();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentActivityRecorder recorder = mock(AgentActivityRecorder.class);
        ToolResultStatusParser statusParser = mock(ToolResultStatusParser.class);

        ToolExecutor executor = new ToolExecutor(
                registry, policy, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                recorder, statusParser, mock(PlanStore.class),
                new ObjectMapper(), mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        Tool webSearch = mock(Tool.class);
        when(webSearch.getName()).thenReturn("web_search");
        when(webSearch.isAvailable(any())).thenReturn(true);
        when(webSearch.execute(anyString(), any()))
                .thenReturn("{\"status\":\"SUCCESS\"}");
        when(registry.find("web_search")).thenReturn(webSearch);
        when(statusParser.parse(anyString()))
                .thenReturn(com.youkeda.exercise.claw.agent.model.ResultStatus.SUCCESS);

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("tc1", "web_search", "{\"query\":\"天气\"}")),
                new ToolExecutionContext("搜索", SkillSession.create("u1"), "u1"),
                SkillSession.create("u1"), null, "req-1", "common", "搜索",
                new HashSet<>());

        assertEquals(1, batch.toolCallCount());
        assertTrue(batch.executedInBatch());
        verify(webSearch).execute(anyString(), any());
    }

    // ==================== 参数化：全部高风险工具 ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "file_delete", "file_update",
            "create_schedule_task", "update_schedule_task", "cancel_schedule_task"
    })
    void allHighRiskToolsShouldBeBlocked(String toolName) {
        SafetyPolicy policy = new SafetyPolicy();
        assertEquals(SafetyPolicy.ToolRiskLevel.HIGH, policy.getRiskLevel(toolName));
        assertEquals("BLOCKED_CONFIRM_REQUIRED", policy.canExecute(toolName, "{}"));
    }
}
