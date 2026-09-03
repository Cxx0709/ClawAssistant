package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.store.ScoutTaskStore;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTask;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ScoutTaskManager {

    private static final Logger log = LoggerFactory.getLogger(ScoutTaskManager.class);

    private final ScoutTaskStore taskStore;

    public ScoutTaskManager(ScoutTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    public ScoutTask createTask(String taskId, String query) {
        ScoutTask task = new ScoutTask(taskId, query,
                ScoutTaskStatus.PENDING, Instant.now(), null, null);
        taskStore.save(task);
        return task;
    }

    /**
     * 在单一任务管理器临界区内完成查重和创建，避免两个并发请求都通过查重。
     */
    public synchronized boolean createTaskIfNoActive(String taskId, String query) {
        if (isDuplicate()) {
            return false;
        }
        createTask(taskId, query);
        return true;
    }

    public void updateStatus(String taskId, ScoutTaskStatus status) {
        taskStore.updateStatus(taskId, status);
    }

    public void updateSummary(String taskId, String summary) {
        taskStore.updateSummary(taskId, summary);
    }

    public Optional<ScoutTask> getTask(String taskId) {
        return taskStore.find(taskId);
    }

    public List<ScoutTask> listTasks() {
        return taskStore.findAll();
    }

    public boolean cancelTask(String taskId) {
        Optional<ScoutTask> task = taskStore.find(taskId);
        if (task.isPresent() && task.get().status() == ScoutTaskStatus.RUNNING) {
            taskStore.updateStatus(taskId, ScoutTaskStatus.CANCELLED);
            return true;
        }
        return false;
    }

    public boolean isDuplicate() {
        List<ScoutTask> recent = taskStore.findAll();
        return recent.stream().anyMatch(t ->
                t.status() == ScoutTaskStatus.RUNNING || t.status() == ScoutTaskStatus.PENDING);
    }
}
