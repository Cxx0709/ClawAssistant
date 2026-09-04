package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ToolExecutorToolStatusTest {

    /** 最小测试桩：返回固定 JSON 结果的工具 */
    static class StubTool extends AbstractTool {
        private final String name;
        private final String resultJson;
        private boolean available = true;

        StubTool(String name, String resultJson, ToolRegistry registry, ObjectMapper om) {
            super(registry, om);
            this.name = name;
            this.resultJson = resultJson;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "stub"; }
        @Override public JsonNode getParameters() { return schema().build(); }
        @Override public String execute(String argumentsJson, ToolExecutionContext context) {
            return resultJson;
        }
        @Override public boolean isAvailable(ToolExecutionContext context) { return available; }
        void setAvailable(boolean available) { this.available = available; }
    }

    @Test
    void recordsSuccessAndBlockedStatusPerTool() {
        ObjectMapper om = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        StubTool ok = new StubTool("weather_query", "{\"status\":\"SUCCESS\"}", registry, om);
        StubTool blocked = new StubTool("didi_ride", "{\"status\":\"BLOCKED\"}", registry, om);
        blocked.setAvailable(false);
        registry.register(ok);
        registry.register(blocked);

        SafetyPolicy allowAll = mock(SafetyPolicy.class);
        when(allowAll.canExecute(any(), any())).thenReturn(null);

        ToolExecutor executor = new ToolExecutor(
                registry, allowAll, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), new ToolResultStatusParser(om),
                mock(PlanStore.class), om, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        LLMResponse.ToolCall callOk = new LLMResponse.ToolCall("c1", "weather_query", "{}");
        LLMResponse.ToolCall callBlocked = new LLMResponse.ToolCall("c2", "didi_ride", "{}");

        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(callOk, callBlocked),
                mock(ToolExecutionContext.class),
                SkillSession.create("u"),
                null, "req", "common", "帮我查天气",
                new HashSet<>());

        Map<String, ResultStatus> statuses = batch.toolStatuses();
        assertEquals(ResultStatus.SUCCESS, statuses.get("weather_query"));
        assertEquals(ResultStatus.BLOCKED, statuses.get("didi_ride"));
        assertTrue(batch.executedInBatch());
    }

    @Test
    void blocksWhenSafetyPolicyRejects() {
        ObjectMapper om = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        StubTool ok = new StubTool("weather_query", "{\"status\":\"SUCCESS\"}", registry, om);
        registry.register(ok);

        // SafetyPolicy 返回拒绝原因 → 工具未执行，状态应为 BLOCKED
        SafetyPolicy blocking = mock(SafetyPolicy.class);
        when(blocking.canExecute(any(), any())).thenReturn("拒绝原因");

        ToolExecutor executor = new ToolExecutor(
                registry, blocking, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), new ToolResultStatusParser(om),
                mock(PlanStore.class), om, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        LLMResponse.ToolCall call = new LLMResponse.ToolCall("c1", "weather_query", "{}");
        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(call), mock(ToolExecutionContext.class),
                SkillSession.create("u"), null, "req", "common", "查天气",
                new HashSet<>());

        assertEquals(ResultStatus.BLOCKED, batch.toolStatuses().get("weather_query"));
    }

    @Test
    void blocksDuplicateCallWithSameToolAndArguments() {
        ObjectMapper om = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        StubTool ok = new StubTool("weather_query", "{\"status\":\"SUCCESS\"}", registry, om);
        registry.register(ok);

        SafetyPolicy allowAll = mock(SafetyPolicy.class);
        when(allowAll.canExecute(any(), any())).thenReturn(null);

        ToolExecutor executor = new ToolExecutor(
                registry, allowAll, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), new ToolResultStatusParser(om),
                mock(PlanStore.class), om, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        // 同一工具 + 相同参数执行两次：第一次成功，第二次被去重为 BLOCKED
        LLMResponse.ToolCall call = new LLMResponse.ToolCall("c1", "weather_query", "{}");
        Set<String> executedCalls = new HashSet<>();

        ToolExecutor.ToolExecutionBatch first = executor.executeToolCalls(
                List.of(call), mock(ToolExecutionContext.class),
                SkillSession.create("u"), null, "req", "common", "查天气",
                executedCalls);
        ToolExecutor.ToolExecutionBatch second = executor.executeToolCalls(
                List.of(call), mock(ToolExecutionContext.class),
                SkillSession.create("u"), null, "req", "common", "查天气",
                executedCalls);

        assertEquals(ResultStatus.SUCCESS, first.toolStatuses().get("weather_query"));
        assertEquals(ResultStatus.BLOCKED, second.toolStatuses().get("weather_query"));
    }

    @Test
    void blocksSemanticallyDuplicateJsonWithDifferentFieldOrder() {
        ObjectMapper om = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        StubTool ok = new StubTool("weather_query", "{\"status\":\"SUCCESS\"}", registry, om);
        registry.register(ok);

        SafetyPolicy allowAll = mock(SafetyPolicy.class);
        when(allowAll.canExecute(any(), any())).thenReturn(null);
        ToolExecutor executor = new ToolExecutor(
                registry, allowAll, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class), mock(AgentActivityRecorder.class),
                new ToolResultStatusParser(om), mock(PlanStore.class), om, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));
        Set<String> executedCalls = new HashSet<>();

        ToolExecutor.ToolExecutionBatch first = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("c1", "weather_query", "{\"city\":\"杭州\",\"days\":2}")),
                mock(ToolExecutionContext.class), SkillSession.create("u"), null,
                "req", "common", "查天气", executedCalls);
        ToolExecutor.ToolExecutionBatch second = executor.executeToolCalls(
                List.of(new LLMResponse.ToolCall("c2", "weather_query", "{\"days\":2,\"city\":\"杭州\"}")),
                mock(ToolExecutionContext.class), SkillSession.create("u"), null,
                "req", "common", "查天气", executedCalls);

        assertEquals(ResultStatus.SUCCESS, first.toolStatuses().get("weather_query"));
        assertEquals(ResultStatus.BLOCKED, second.toolStatuses().get("weather_query"));
    }

    @Test
    void recordsFailedForUnknownTool() {
        ObjectMapper om = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        SafetyPolicy allowAll = mock(SafetyPolicy.class);
        when(allowAll.canExecute(any(), any())).thenReturn(null);
        ToolExecutor executor = new ToolExecutor(
                registry, allowAll, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), new ToolResultStatusParser(om),
                mock(PlanStore.class), om, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

        LLMResponse.ToolCall call = new LLMResponse.ToolCall("c1", "no_such_tool", "{}");
        ToolExecutor.ToolExecutionBatch batch = executor.executeToolCalls(
                List.of(call), mock(ToolExecutionContext.class),
                SkillSession.create("u"), null, "req", "common", "x", new HashSet<>());

        assertEquals(ResultStatus.FAILED, batch.toolStatuses().get("no_such_tool"));
    }
}
