package com.youkeda.exercise.claw.agent.model;

import java.util.List;

/**
 * 单个任务的完整生命周期。
 *
 * <p>{@link ExecutionStatus} 由 Runtime 管理，{@link EvaluationState} 由 LLM 管理。
 */
public class PlanTask {

    /** 唯一标识，LLM 分配 */
    private String id;

    /** 任务描述（如"查询北京天气"） */
    private String description;

    /** 依赖的任务 ID 列表 */
    private List<String> dependencies;

    /** Runtime 管理 —— 执行状态 */
    private ExecutionStatus executionStatus;

    /** LLM 管理 —— 语义评价 */
    private EvaluationState evaluationState;

    /** 工具执行的结果（Runtime 写入原始数据，LLM 写入摘要） */
    private TaskResult result;

    public PlanTask() {}

    public PlanTask(String id, String description, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.dependencies = dependencies;
        this.executionStatus = ExecutionStatus.PENDING;
        this.evaluationState = EvaluationState.UNEVALUATED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public ExecutionStatus getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(ExecutionStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public EvaluationState getEvaluationState() { return evaluationState; }
    public void setEvaluationState(EvaluationState evaluationState) {
        this.evaluationState = evaluationState;
    }

    public TaskResult getResult() { return result; }
    public void setResult(TaskResult result) { this.result = result; }
}
