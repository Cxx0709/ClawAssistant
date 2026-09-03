package com.youkeda.exercise.claw.feature.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 定时任务创建器（共享服务）
 *
 * <p>封装 {@link ScheduledTask} 的创建逻辑，被多个 LLM Function 复用：
 * <ul>
 *   <li>{@link CreateScheduleTaskFunction} — 单任务创建</li>
 *   <li>{@link com.youkeda.exercise.claw.feature.task.planning.ExecutePlanTasksFunction} — 批量执行计划</li>
 * </ul>
 *
 * <p>职责：
 * <ul>
 *   <li>参数校验（content、时间格式）</li>
 *   <li>时间计算（delay_minutes → execute_time）</li>
 *   <li>周期类型处理</li>
 *   <li>持久化保存</li>
 * </ul>
 */
@Component
public class TaskCreator {

    private static final Logger log = LoggerFactory.getLogger(TaskCreator.class);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_DELAY_MINUTES = 30 * 24 * 60;

    private final ScheduledTaskRepository taskRepository;

    public TaskCreator(ScheduledTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 从 LLM 参数节点创建任务
     *
     * @param userId   用户标识
     * @param taskNode JSON 节点，包含 content, delay_minutes/execute_time, repeat_type(可选)
     * @return 创建结果描述
     */
    public CreateResult createFromNode(String userId, JsonNode taskNode) {
        // 提取 content
        String content = taskNode.has("content") ? taskNode.get("content").asText().strip() : "";
        if (content.isEmpty()) {
            return CreateResult.failure("缺少提醒内容");
        }
        if (content.length() > 200) {
            content = content.substring(0, 200);
        }

        // 解析执行时间
        boolean hasDelay = taskNode.has("delay_minutes") && taskNode.get("delay_minutes").canConvertToExactIntegral();
        boolean hasExecuteTime = taskNode.has("execute_time") && !taskNode.get("execute_time").asText().isBlank();

        if (!hasDelay && !hasExecuteTime) {
            return CreateResult.failure("任务「" + content + "」缺少时间参数");
        }

        LocalDateTime executeTime;
        Integer delayMinutes = null;

        if (hasExecuteTime) {
            String timeStr = taskNode.get("execute_time").asText().strip();
            if (!timeStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                return CreateResult.failure("execute_time 格式无效，请使用 yyyy-MM-dd HH:mm:ss");
            }
            try {
                executeTime = LocalDateTime.parse(timeStr, DTF);
            } catch (DateTimeParseException e) {
                return CreateResult.failure("execute_time 格式无效: " + timeStr);
            }
            if (executeTime.isBefore(LocalDateTime.now())) {
                return CreateResult.failure("执行时间不能早于当前时间");
            }
        } else {
            delayMinutes = taskNode.get("delay_minutes").asInt();
            if (delayMinutes < 1 || delayMinutes > MAX_DELAY_MINUTES) {
                return CreateResult.failure("delay_minutes 需在 1~43200 之间");
            }
            executeTime = LocalDateTime.now().plusMinutes(delayMinutes);
        }

        // 解析重复类型
        String repeatType = taskNode.has("repeat_type") && !taskNode.get("repeat_type").asText().isBlank()
                ? taskNode.get("repeat_type").asText().toUpperCase()
                : null;

        // 统一规范化：null / ONCE → NONE
        repeatType = ScheduledTask.normalizeRepeatType(repeatType);

        if (!ScheduledTask.REPEAT_TYPES.contains(repeatType)) {
            return CreateResult.failure("不支持的周期类型: " + repeatType + "，仅支持 NONE/DAILY/WEEKLY/MONTHLY");
        }

        // 解析任务类型
        String taskType = taskNode.has("task_type") && !taskNode.get("task_type").asText().isBlank()
                ? taskNode.get("task_type").asText().toUpperCase()
                : null;
        if (taskType == null) {
            taskType = ScheduledTask.TASK_TYPE_REMINDER;
        } else if (!ScheduledTask.TASK_TYPES.contains(taskType)) {
            return CreateResult.failure("不支持的任务类型: " + taskType + "，仅支持 REMINDER/AGENT");
        }

        // 创建并保存任务
        ScheduledTask task = new ScheduledTask(userId, content, executeTime);
        task.setRepeatType(repeatType);
        task.setNextExecuteTime(executeTime);
        task.setTaskType(taskType);
        taskRepository.save(task);

        log.info("TaskCreator 创建任务成功 | id={} | userId={} | content={} | repeat={} | executeTime={}",
                task.getId(), userId, content, repeatType, task.getExecuteTimeAsString());

        return CreateResult.success(task, delayMinutes);
    }

    // ==================== 结果类型 ====================

    /**
     * 创建结果
     */
    public static class CreateResult {
        private final boolean success;
        private final ScheduledTask task;
        private final String errorMessage;
        private final Integer delayMinutes;

        private CreateResult(boolean success, ScheduledTask task, String errorMessage, Integer delayMinutes) {
            this.success = success;
            this.task = task;
            this.errorMessage = errorMessage;
            this.delayMinutes = delayMinutes;
        }

        public static CreateResult success(ScheduledTask task, Integer delayMinutes) {
            return new CreateResult(true, task, null, delayMinutes);
        }

        public static CreateResult failure(String message) {
            return new CreateResult(false, null, message, null);
        }

        public boolean isSuccess() { return success; }
        public ScheduledTask getTask() { return task; }
        public String getErrorMessage() { return errorMessage; }
        public Integer getDelayMinutes() { return delayMinutes; }
    }
}