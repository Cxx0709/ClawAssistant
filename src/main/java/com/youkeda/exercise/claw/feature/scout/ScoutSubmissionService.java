package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.ScoutTaskManager;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import com.youkeda.exercise.claw.skill.WorkflowRegistry;
import com.youkeda.exercise.claw.skill.WorkflowDefinition;
import com.youkeda.exercise.claw.skill.WorkflowRequest;
import com.youkeda.exercise.claw.skill.WorkflowResult;
import com.youkeda.exercise.claw.skill.WorkflowWorker;
import com.youkeda.exercise.claw.feature.scout.notifier.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ScoutSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ScoutSubmissionService.class);

    private final ScoutTaskManager taskManager;
    private final WorkflowRegistry workflowRegistry;
    private final NotificationService notificationService;

    public ScoutSubmissionService(ScoutTaskManager taskManager,
                                  WorkflowRegistry workflowRegistry,
                                  NotificationService notificationService) {
        this.taskManager = taskManager;
        this.workflowRegistry = workflowRegistry;
        this.notificationService = notificationService;
    }

    public ScoutSubmissionResult submit(String query, String workflowName) {
        return submit(ScoutExecutionContext.withoutKnowledge(query), workflowName);
    }

    public ScoutSubmissionResult submit(ScoutExecutionContext context, String workflowName) {
        try {
            Optional<WorkflowDefinition> definition = workflowRegistry.find(workflowName);
            Optional<WorkflowWorker> worker = workflowRegistry.getWorker(workflowName);
            if (definition.isEmpty() || worker.isEmpty()) {
                return ScoutSubmissionResult.unavailable("信息猎手工作流不可用");
            }

            ScoutExecutionContext normalizedContext = context == null
                    ? ScoutExecutionContext.withoutKnowledge("") : context;
            String normalizedQuery = normalizedContext.explicitQuery();
            String taskId = UUID.randomUUID().toString();
            if (!taskManager.createTaskIfNoActive(taskId, normalizedQuery)) {
                return ScoutSubmissionResult.duplicate();
            }
            try {
                CompletableFuture.runAsync(() -> executeWorkflow(
                        worker.get(), definition.get(), taskId, workflowName, normalizedContext));
            } catch (RuntimeException schedulingError) {
                log.error("调度信息猎手工作流失败 | taskId={}", taskId, schedulingError);
                taskManager.updateStatus(taskId, ScoutTaskStatus.FAILED);
                notificationService.notifyFailure();
                return ScoutSubmissionResult.failed(schedulingError.getMessage());
            }
            return ScoutSubmissionResult.started(taskId);
        } catch (RuntimeException e) {
            log.error("提交信息猎手任务失败", e);
            return ScoutSubmissionResult.failed(e.getMessage());
        }
    }

    private void executeWorkflow(WorkflowWorker worker, WorkflowDefinition definition, String taskId,
                                 String workflowName, ScoutExecutionContext context) {
        try {
            WorkflowResult result = worker.execute(new WorkflowRequest(
                    taskId, workflowName, ScoutWorkflowPayload.encode(context), Instant.now(),
                    definition.timeout(), definition.retryMax()));
            if (result == null
                    || result.status() == WorkflowResult.WorkflowStatus.FAILED
                    || result.status() == WorkflowResult.WorkflowStatus.TIMEOUT) {
                taskManager.updateStatus(taskId, ScoutTaskStatus.FAILED);
                notificationService.notifyFailure();
            } else if (result.status() == WorkflowResult.WorkflowStatus.CANCELLED) {
                taskManager.updateStatus(taskId, ScoutTaskStatus.CANCELLED);
            }
        } catch (RuntimeException e) {
            log.error("异步信息猎手工作流执行失败 | taskId={}", taskId, e);
            taskManager.updateStatus(taskId, ScoutTaskStatus.FAILED);
            notificationService.notifyFailure();
        }
    }
}
