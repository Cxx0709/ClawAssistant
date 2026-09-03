package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import com.youkeda.exercise.claw.skill.WorkflowRequest;
import com.youkeda.exercise.claw.skill.WorkflowResult;
import com.youkeda.exercise.claw.skill.WorkflowWorker;
import com.youkeda.exercise.claw.feature.scout.ScoutOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

@Component("scoutWorkflowWorker")
public class ScoutWorkflowWorker implements WorkflowWorker {

    private static final Logger log = LoggerFactory.getLogger(ScoutWorkflowWorker.class);

    private final ScoutOrchestrator orchestrator;
    private final ScoutTaskManager taskManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scout-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${workflow.timeout-minutes:30}")
    private int timeoutMinutes;

    @Value("${workflow.retry-max:3}")
    private int retryMax;

    public ScoutWorkflowWorker(ScoutOrchestrator orchestrator,
                               ScoutTaskManager taskManager) {
        this.orchestrator = orchestrator;
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "scoutWorkflowWorker";
    }

    @Override
    public WorkflowResult execute(WorkflowRequest request) {
        String taskId = request.taskId();

        Duration timeout = request.timeout() == null
                ? Duration.ofMinutes(timeoutMinutes)
                : request.timeout();
        int effectiveRetryMax = request.retryMax() < 0
                ? retryMax
                : request.retryMax();
        Exception lastError = null;

        for (int attempt = 0; attempt <= effectiveRetryMax; attempt++) {
            if (attempt > 0) {
                log.info("Retry {}/{} for scout workflow, task={}",
                        attempt, effectiveRetryMax, taskId);
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) { break; }
            }

            taskManager.updateStatus(taskId, ScoutTaskStatus.RUNNING);

            Future<WorkflowResult> future = null;
            try {
                future = executor.submit(() -> {
                    ScoutExecutionContext context = ScoutWorkflowPayload.decode(request.payload());
                    com.youkeda.exercise.claw.feature.scout.ScoutReport report =
                            orchestrator.run(context);
                    String summary = report.toString();
                    return new WorkflowResult(taskId, WorkflowResult.WorkflowStatus.COMPLETED,
                            Instant.now(), summary, null);
                });

                WorkflowResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                taskManager.updateStatus(taskId, ScoutTaskStatus.COMPLETED);
                taskManager.updateSummary(taskId, result.summary());
                log.info("Scout workflow completed: taskId={}", taskId);
                return result;

            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                lastError = e;
                log.warn("Scout workflow timeout attempt {}/{}",
                        attempt + 1, effectiveRetryMax + 1);
                taskManager.updateStatus(taskId, ScoutTaskStatus.PENDING);
            } catch (InterruptedException e) {
                if (future != null) {
                    future.cancel(true);
                }
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (Exception e) {
                lastError = e;
                log.error("Scout workflow failed attempt {}/{}",
                        attempt + 1, effectiveRetryMax + 1, e);
                taskManager.updateStatus(taskId, ScoutTaskStatus.PENDING);
            }
        }

        taskManager.updateStatus(taskId, ScoutTaskStatus.FAILED);
        String failureMessage = lastError == null
                ? "Workflow interrupted before completion"
                : lastError.getMessage();
        return new WorkflowResult(taskId, WorkflowResult.WorkflowStatus.FAILED,
                Instant.now(), "All retries exhausted: " + failureMessage, null);
    }
}
