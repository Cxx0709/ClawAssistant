package com.youkeda.exercise.claw.skill;

import java.time.Duration;

public record WorkflowDefinition(String name, String workerName, Duration timeout, int retryMax, String cron) {}
