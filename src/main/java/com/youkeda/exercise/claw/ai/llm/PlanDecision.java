package com.youkeda.exercise.claw.ai.llm;

import java.util.List;

/**
 * LLM 产出的结构化计划决策。
 *
 * <p>和 {@code PlanState} 的区别：{@code PlanDecision} 是 LLM 一次输出的快照，
 * {@code PlanState} 是跨多次 LLM 调用的会话级累积状态。
 */
public class PlanDecision {

    /** 会话目标 */
    private String goal;

    /** 计划的任务列表 */
    private List<TaskDefinition> tasks;

    public PlanDecision() {}

    public PlanDecision(String goal, List<TaskDefinition> tasks) {
        this.goal = goal;
        this.tasks = tasks;
    }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public List<TaskDefinition> getTasks() { return tasks; }
    public void setTasks(List<TaskDefinition> tasks) { this.tasks = tasks; }
}
