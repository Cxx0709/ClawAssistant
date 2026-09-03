package com.youkeda.exercise.claw.ai.llm;

import java.util.List;

/**
 * {@link PlanDecision} 中的单个任务定义。
 */
public class TaskDefinition {

    private String id;
    private String description;
    private List<String> dependencies;

    public TaskDefinition() {}

    public TaskDefinition(String id, String description, List<String> dependencies) {
        this.id = id;
        this.description = description;
        this.dependencies = dependencies;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
}
