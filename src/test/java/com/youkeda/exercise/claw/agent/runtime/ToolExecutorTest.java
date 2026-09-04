
package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.model.ExecutionStatus;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.skill.PendingToolCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 2：findTaskByToolName 改为 PlanState DAG 就绪匹配的单元测试。
 *
 * <p>通过 executeToolCalls 驱动：依赖未满足的任务不应被标记 DONE（DAG 就绪语义）。
 */
class ToolExecutorTest {

    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final SafetyPolicy safetyPolicy = mock(SafetyPolicy.class);
    private final PlanStore planStore = mock(PlanStore.class);
    private final ToolResultStatusParser statusParser = mock(ToolResultStatusParser.class);
    private final ToolExecutor executor = new ToolExecutor(
            registry, safetyPolicy, mock(SkillPendingCoordinator.class),
            mock(PendingToolCoordinator.class),
            mock(AgentActivityRecorder.class), statusParser, planStore, new ObjectMapper(), mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));

    private final ToolExecutionBatchRunner runner = new ToolExecutionBatchRunner();

    @Test
    void dependencyNotSatisfiedShouldNotMarkDone() {
        // A（无依赖）PENDING，B 依赖 A 且 PENDING；工具名 weather_query 直接匹配 B 的 description
        PlanTask a = new PlanTask("A", "查询北京天气", List.of());
        PlanTask b = new PlanTask("B", "使用 weather_query 查询上海天气", List.of("A"));
        PlanState plan = new PlanState("出行计划", List.of(a, b));
        stubTool("weather_query");

        runner.execute("weather_query", plan);

        // DAG 门：B 依赖未满足（A 仍 PENDING）→ 即使工具名匹配 B，B 也不被标记 DONE
        assertEquals(ExecutionStatus.PENDING, b.getExecutionStatus(),
                "依赖未满足的任务不应被标记 DONE");
        // A 是唯一就绪任务（无依赖），回退命中 A → 计划正常前进
        assertEquals(ExecutionStatus.DONE, a.getExecutionStatus());
        verify(planStore).save(plan);
    }

    @Test
    void dependencySatisfiedShouldMarkDone() {
        PlanTask a = new PlanTask("A", "查询北京天气", List.of());
        PlanTask b = new PlanTask("B", "根据天气建议出行", List.of("A"));
        PlanState plan = new PlanState("出行计划", List.of(a, b));
        a.setExecutionStatus(ExecutionStatus.DONE);
        stubTool("weather_query");

        runner.execute("weather_query", plan);

        // A 已完成 → B 就绪且 description 匹配 → B 标记 DONE
        assertEquals(ExecutionStatus.DONE, b.getExecutionStatus());
        verify(planStore).save(plan);
    }

    @Test
    void matchesReadyTaskByDescriptionPriority() {
        // 两个都就绪的任务，工具名 weather_query 只匹配 B 的 description
        PlanTask a = new PlanTask("A", "查询北京天气", List.of());
        PlanTask b = new PlanTask("B", "使用 weather_query 查询上海天气", List.of());
        PlanState plan = new PlanState("查询天气", List.of(a, b));
        stubTool("weather_query");

        runner.execute("weather_query", plan);

        // description 匹配优先于首个就绪任务：B 被标记，A 不动
        assertEquals(ExecutionStatus.PENDING, a.getExecutionStatus());
        assertEquals(ExecutionStatus.DONE, b.getExecutionStatus());
    }

    @Test
    void noReadyTasksLeavesPlanUntouched() {
        // 唯一任务依赖一个不存在的任务 → 永不就绪
        PlanTask a = new PlanTask("A", "查询北京天气", List.of("ghost"));
        PlanState plan = new PlanState("查询天气", List.of(a));
        stubTool("weather_query");

        runner.execute("weather_query", plan);

        assertEquals(ExecutionStatus.PENDING, a.getExecutionStatus());
        verify(planStore, never()).save(any());
    }

    private void stubTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.isAvailable(any())).thenReturn(true);
        when(tool.execute(anyString(), any())).thenReturn("{\"status\":\"ok\"}");
        when(registry.find(name)).thenReturn(tool);
        when(statusParser.parse(anyString()))
                .thenReturn(com.youkeda.exercise.claw.agent.model.ResultStatus.SUCCESS);
    }

    private class ToolExecutionBatchRunner {
        void execute(String toolName, PlanState plan) {
            LLMResponse.ToolCall call = new LLMResponse.ToolCall("call-1", toolName, "{}");
            SkillSession session = SkillSession.create("owner");
            executor.executeToolCalls(
                    List.of(call),
                    new ToolExecutionContext("请查询天气", session, "owner"),
                    session, plan, "req-1", "common", "请查询天气",
                    new HashSet<>());
        }
    }
}
