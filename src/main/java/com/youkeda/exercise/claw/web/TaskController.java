package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 定时/盯守任务只读 + 轻管理端点（Agent 雷达页数据源）。
 *
 * <p>任务模型与持久化复用 {@link ScheduledTaskRepository}，
 * 这里只做 HTTP 映射与归属校验，不承载业务逻辑。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduledTaskRepository tasks;
    private final AuthenticatedUser authenticatedUser;

    public TaskController(ScheduledTaskRepository tasks, AuthenticatedUser authenticatedUser) {
        this.tasks = tasks;
        this.authenticatedUser = authenticatedUser;
    }

    /**
     * 当前用户的任务列表。
     *
     * @param type     可选，按任务类型过滤（REMINDER / AGENT），缺省返回全部
     * @param status   可选，按状态过滤（ACTIVE / PAUSED / DONE / CANCELLED / FAILED），缺省返回全部
     */
    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status) {
        String userId = authenticatedUser.require(authentication).id();
        List<ScheduledTask> items = (type != null && !type.isEmpty())
                ? tasks.findByUserIdAndType(userId, type)
                : tasks.findByUserId(userId);
        return items.stream()
                .filter(task -> status == null || status.isEmpty() || status.equals(task.getStatus()))
                .map(TaskController::toView)
                .toList();
    }

    /** 暂停任务（仅 Agent 任务支持 PAUSED 语义，其他类型原样返回 false） */
    @PostMapping("/{id}/pause")
    public Map<String, Boolean> pause(Authentication authentication, @PathVariable long id) {
        String userId = authenticatedUser.require(authentication).id();
        return Map.of("updated", tasks.markPaused(id, userId));
    }

    /** 恢复任务（PAUSED → ACTIVE） */
    @PostMapping("/{id}/resume")
    public Map<String, Boolean> resume(Authentication authentication, @PathVariable long id) {
        String userId = authenticatedUser.require(authentication).id();
        return Map.of("updated", tasks.markResumed(id, userId));
    }

    /** 取消任务 */
    @PostMapping("/{id}/cancel")
    public Map<String, Boolean> cancel(Authentication authentication, @PathVariable long id) {
        String userId = authenticatedUser.require(authentication).id();
        return Map.of("updated", tasks.markCancelled(id, userId));
    }

    private static Map<String, Object> toView(ScheduledTask task) {
        return Map.ofEntries(
                Map.entry("id", task.getId()),
                Map.entry("content", task.getContent() != null ? task.getContent() : ""),
                Map.entry("taskType", task.getTaskType()),
                Map.entry("repeatType", task.getRepeatType() != null ? task.getRepeatType() : ScheduledTask.REPEAT_TYPE_NONE),
                Map.entry("repeatInterval", task.getRepeatInterval() != null ? task.getRepeatInterval() : 1),
                Map.entry("status", task.getStatus() != null ? task.getStatus() : ScheduledTask.STATUS_ACTIVE),
                Map.entry("executeTime", format(task.getExecuteTime())),
                Map.entry("nextExecuteTime", format(task.getNextExecuteTime())),
                Map.entry("createdTime", format(task.getCreatedTime())),
                Map.entry("failureCount", task.getFailureCount())
        );
    }

    private static String format(LocalDateTime time) {
        return time != null ? time.format(DTF) : null;
    }
}
