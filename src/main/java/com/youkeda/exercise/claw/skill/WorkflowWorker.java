package com.youkeda.exercise.claw.skill;

public interface WorkflowWorker {

    String getName();

    WorkflowResult execute(WorkflowRequest request);
}
