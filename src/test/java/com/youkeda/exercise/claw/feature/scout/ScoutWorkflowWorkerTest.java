package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTask;
import com.youkeda.exercise.claw.skill.WorkflowRequest;
import com.youkeda.exercise.claw.skill.WorkflowResult;

import com.youkeda.exercise.claw.feature.scout.ScoutOrchestrator;
import com.youkeda.exercise.claw.feature.scout.ScoutReport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ScoutWorkflowWorkerTest {

    @Test
    void updatesTheTaskIdAllocatedByTheEntrypoint() {
        ScoutOrchestrator orchestrator = mock(ScoutOrchestrator.class);
        ScoutTaskManager taskManager = mock(ScoutTaskManager.class);
        when(orchestrator.run(ScoutExecutionContext.withoutKnowledge("AI agents")))
                .thenReturn(new ScoutReport(1, 2, 1));

        ScoutWorkflowWorker worker = new ScoutWorkflowWorker(orchestrator, taskManager);
        ReflectionTestUtils.setField(worker, "timeoutMinutes", 1);
        ReflectionTestUtils.setField(worker, "retryMax", 0);

        WorkflowRequest request = new WorkflowRequest(
                "task-123", "scoutWorkflow", "AI agents", Instant.now());

        WorkflowResult result = worker.execute(request);

        assertEquals("task-123", result.taskId());
        verify(taskManager, never()).createTask(anyString(), anyString());
        verify(taskManager).updateStatus("task-123", ScoutTaskStatus.RUNNING);
        verify(taskManager).updateStatus("task-123", ScoutTaskStatus.COMPLETED);
        verify(orchestrator).run(ScoutExecutionContext.withoutKnowledge("AI agents"));
    }

    @Test
    void interruptsTimedOutAttemptBeforeReturningFailure() {
        ScoutOrchestrator orchestrator = mock(ScoutOrchestrator.class);
        ScoutTaskManager taskManager = mock(ScoutTaskManager.class);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(orchestrator.run(ScoutExecutionContext.withoutKnowledge("slow query"))).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000);
                return new ScoutReport(0, 0, 0);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        });
        ScoutWorkflowWorker worker = new ScoutWorkflowWorker(orchestrator, taskManager);

        WorkflowResult result = worker.execute(new WorkflowRequest(
                "task-timeout", "scoutWorkflow", "slow query", Instant.now(),
                Duration.ofMillis(50), 0));

        assertEquals(WorkflowResult.WorkflowStatus.FAILED, result.status());
        assertTrue(interrupted.get());
        verify(taskManager).updateStatus("task-timeout", ScoutTaskStatus.FAILED);
    }
}
