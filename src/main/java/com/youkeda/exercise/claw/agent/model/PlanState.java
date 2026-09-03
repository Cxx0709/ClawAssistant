package com.youkeda.exercise.claw.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话级结构化意图状态。
 *
 * <p>{@code PlanState} 是 {@code AgentContext} 的可选字段——非多步骤任务时为 null，0 额外开销。
 * 由 Runtime 维护，LLM 通过 Structured Output（{@code PlanDecision}）更新。
 */
public class PlanState {

    /** LLM 声明的会话目标 */
    private String goal;

    /** 当前计划的任务列表 */
    private List<PlanTask> tasks;

    /** 会话版本号，单调递增 */
    private int version = 0;

    public PlanState() {}

    public PlanState(String goal, List<PlanTask> tasks) {
        this.goal = goal;
        this.tasks = tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
    }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public List<PlanTask> getTasks() { return tasks; }
    public void setTasks(List<PlanTask> tasks) {
        this.tasks = tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
    }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    /** 查找指定 ID 的任务 */
    public PlanTask findTask(String taskId) {
        if (taskId == null || tasks == null) return null;
        return tasks.stream()
                .filter(t -> taskId.equals(t.getId()))
                .findFirst().orElse(null);
    }

    /**
     * 获取所有 PENDING 状态且依赖已满足的任务（ready 任务）。
     *
     * <p>依赖满足 = 依赖任务执行完成（DONE）或被语义替换（SUPERSEDED）——
     * 与全计划完成判定（{@code ExecutionLoop}）对 SUPERSEDED 的终态处理保持一致。
     *
     * <p>{@code @JsonIgnore}：派生辅助方法（无 setter 且返回不可变列表），
     * Jackson 会把它当属性序列化/反序列化导致 {@code UnsupportedOperationException}。
     */
    @JsonIgnore
    public List<PlanTask> getReadyTasks() {
        if (tasks == null) return List.of();
        return tasks.stream()
                .filter(t -> t.getExecutionStatus() == ExecutionStatus.PENDING
                        && (t.getDependencies() == null || t.getDependencies().isEmpty()
                            || t.getDependencies().stream()
                                .allMatch(depId -> {
                                    PlanTask dep = findTask(depId);
                                    return dep != null && isDependencySatisfied(dep);
                                })))
                .toList();
    }

    private static boolean isDependencySatisfied(PlanTask dep) {
        return dep.getExecutionStatus() == ExecutionStatus.DONE
                || dep.getEvaluationState() == EvaluationState.SUPERSEDED;
    }
}
