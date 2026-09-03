package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.ScoutTaskManager;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import com.youkeda.exercise.claw.skill.WorkflowDefinition;
import com.youkeda.exercise.claw.skill.WorkflowRegistry;
import com.youkeda.exercise.claw.skill.WorkflowRequest;
import com.youkeda.exercise.claw.skill.WorkflowWorker;
import com.youkeda.exercise.claw.feature.scout.notifier.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoutSubmissionServiceTest {

    @Test
    void submitsProfileDiscoveryToConfiguredWorkflow() {
        ScoutTaskManager taskManager = mock(ScoutTaskManager.class);
        WorkflowRegistry workflowRegistry = mock(WorkflowRegistry.class);
        WorkflowWorker worker = mock(WorkflowWorker.class);
        NotificationService notificationService = mock(NotificationService.class);
        when(workflowRegistry.getWorker("scoutWorkflow")).thenReturn(Optional.of(worker));
        when(workflowRegistry.find("scoutWorkflow")).thenReturn(Optional.of(
                new WorkflowDefinition(
                        "scoutWorkflow", "scoutWorkflowWorker",
                        Duration.ofSeconds(12), 1, null)));
        when(taskManager.createTaskIfNoActive(anyString(), eq(""))).thenReturn(true);
        when(worker.execute(any())).thenReturn(new com.youkeda.exercise.claw.skill.WorkflowResult(
                "task", com.youkeda.exercise.claw.skill.WorkflowResult.WorkflowStatus.COMPLETED,
                Instant.now(), "done", null));
        ScoutSubmissionService service = new ScoutSubmissionService(
                taskManager, workflowRegistry, notificationService);

        ScoutExecutionContext context = new ScoutExecutionContext(
                "", "planning", "decision");
        ScoutSubmissionResult result = service.submit(context, "scoutWorkflow");

        assertEquals(ScoutSubmissionResult.Status.STARTED, result.status());
        verify(taskManager).createTaskIfNoActive(anyString(), eq(""));
        var request = org.mockito.ArgumentCaptor.forClass(WorkflowRequest.class);
        verify(worker, timeout(1000)).execute(request.capture());
        assertEquals(Duration.ofSeconds(12), request.getValue().timeout());
        assertEquals(1, request.getValue().retryMax());
        assertEquals(context, ScoutWorkflowPayload.decode(request.getValue().payload()));
    }

    @Test
    void marksTaskFailedAndNotifiesUserWhenWorkerThrows() {
        ScoutTaskManager taskManager = mock(ScoutTaskManager.class);
        WorkflowRegistry workflowRegistry = mock(WorkflowRegistry.class);
        WorkflowWorker worker = mock(WorkflowWorker.class);
        NotificationService notificationService = mock(NotificationService.class);
        WorkflowDefinition definition = new WorkflowDefinition(
                "scoutWorkflow", "scoutWorkflowWorker",
                Duration.ofSeconds(12), 1, null);
        when(workflowRegistry.find("scoutWorkflow")).thenReturn(Optional.of(definition));
        when(workflowRegistry.getWorker("scoutWorkflow")).thenReturn(Optional.of(worker));
        when(taskManager.createTaskIfNoActive(anyString(), eq("AI Agent"))).thenReturn(true);
        when(worker.execute(any())).thenThrow(new IllegalStateException("worker unavailable"));
        ScoutSubmissionService service = new ScoutSubmissionService(
                taskManager, workflowRegistry, notificationService);

        service.submit("AI Agent", "scoutWorkflow");

        var taskId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(taskManager).createTaskIfNoActive(taskId.capture(), eq("AI Agent"));
        verify(taskManager, timeout(1000)).updateStatus(
                taskId.getValue(), ScoutTaskStatus.FAILED);
        verify(notificationService, timeout(1000)).notifyFailure();
    }

    @Test
    void treatsTimeoutResultAsFailedTerminalState() {
        ScoutTaskManager taskManager = mock(ScoutTaskManager.class);
        WorkflowRegistry workflowRegistry = mock(WorkflowRegistry.class);
        WorkflowWorker worker = mock(WorkflowWorker.class);
        NotificationService notificationService = mock(NotificationService.class);
        WorkflowDefinition definition = new WorkflowDefinition(
                "scoutWorkflow", "scoutWorkflowWorker",
                Duration.ofSeconds(12), 0, null);
        when(workflowRegistry.find("scoutWorkflow")).thenReturn(Optional.of(definition));
        when(workflowRegistry.getWorker("scoutWorkflow")).thenReturn(Optional.of(worker));
        when(taskManager.createTaskIfNoActive(anyString(), eq("AI Agent"))).thenReturn(true);
        when(worker.execute(any())).thenReturn(new com.youkeda.exercise.claw.skill.WorkflowResult(
                "task", com.youkeda.exercise.claw.skill.WorkflowResult.WorkflowStatus.TIMEOUT,
                Instant.now(), "timeout", null));
        ScoutSubmissionService service = new ScoutSubmissionService(
                taskManager, workflowRegistry, notificationService);

        service.submit("AI Agent", "scoutWorkflow");

        var taskId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(taskManager).createTaskIfNoActive(taskId.capture(), eq("AI Agent"));
        verify(taskManager, timeout(1000)).updateStatus(
                taskId.getValue(), ScoutTaskStatus.FAILED);
        verify(notificationService, timeout(1000)).notifyFailure();
    }
}
