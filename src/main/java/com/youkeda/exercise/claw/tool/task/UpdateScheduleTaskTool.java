package com.youkeda.exercise.claw.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 修改定时任务 LLM Function
 *
 * <p>注册名称：{@code update_schedule_task}
 *
 * <p>支持修改：提醒内容、执行时间、周期类型。
 * 只允许修改 ACTIVE 状态的任务。DONE 或 CANCELLED 的任务拒绝修改。
 */
@Component
public class UpdateScheduleTaskTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateScheduleTaskTool.class);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduledTaskRepository taskRepository;

    public UpdateScheduleTaskTool(ObjectMapper objectMapper,
                                      ToolRegistry functionRegistry,
                                      ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "update_schedule_task";
    }

    @Override
    public String getDescription() {
        return "修改已创建的定时提醒任务。\n"
                + "当用户要求修改提醒时调用。例如「把明天会议改到下午3点」「修改提醒内容为XXX」。\n"
                + "使用步骤：\n"
                + "1. 先调用 list_schedule_tasks 找到目标任务，获取 task_id\n"
                + "2. 再调用此函数传入 task_id 和要修改的字段\n\n"
                + "可修改字段：\n"
                + "- content: 提醒内容\n"
                + "- execute_time: 执行时间（格式 yyyy-MM-dd HH:mm:ss）\n"
                + "- repeat_type: 周期类型 ONCE/DAILY/WEEKLY\n\n"
                + "注意：只能修改 ACTIVE 状态的任务。已完成或已取消的任务无法修改。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode repeatType = objectMapper.createObjectNode();
        repeatType.put("type", "string");
        repeatType.put("description", "【可选】新的周期类型。ONCE=一次性, DAILY=每天, WEEKLY=每周。不修改则省略。");
        repeatType.putArray("enum").add("NONE").add("ONCE").add("DAILY").add("WEEKLY").add("MONTHLY");

        return schema()
                .integer("task_id", "要修改的定时任务 ID。从 list_schedule_tasks 的结果中获取。", true)
                .string("content", "【可选】新的提醒内容。不修改则省略。", false)
                .string("execute_time", "【可选】新的执行时间，格式 yyyy-MM-dd HH:mm:ss。不修改则省略。", false)
                .raw("repeat_type", repeatType, false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String userId = context.userId();

            if (userId == null || userId.isBlank()) {
                return "{\"error\": \"缺少用户ID\"}";
            }
            if (!args.has("task_id") || !args.get("task_id").canConvertToExactIntegral()) {
                return "{\"error\": \"缺少或无效的 task_id\"}";
            }

            long taskId = args.get("task_id").asLong();

            // === 校验任务 ===
            ScheduledTask task = taskRepository.findById(taskId);
            if (task == null) {
                return "{\"error\": \"未找到 ID 为 " + taskId + " 的定时任务\"}";
            }
            if (!userId.equals(task.getUserId())) {
                log.warn("用户尝试修改非自己的任务 | userId={} | taskUserId={}", userId, task.getUserId());
                return "{\"error\": \"无权修改该任务\"}";
            }
            if (!task.isActive()) {
                return "{\"error\": \"任务状态为「" + task.getStatusDisplay()
                        + "」，无法修改（仅可修改待执行的任务）\"}";
            }

            // === 解析要修改的字段 ===
            boolean changed = false;
            StringBuilder changeLog = new StringBuilder();

            // content
            if (args.has("content") && !args.get("content").asText().isBlank()) {
                String newContent = args.get("content").asText().strip();
                task.setContent(newContent);
                changeLog.append("内容->").append(newContent).append(" ");
                changed = true;
            }

            // repeat_type
            if (args.has("repeat_type") && !args.get("repeat_type").asText().isBlank()) {
                String newRepeat = args.get("repeat_type").asText().toUpperCase();
                if (!ScheduledTask.REPEAT_TYPES.contains(newRepeat)) {
                    return "{\"error\": \"不支持的周期类型: " + newRepeat + "\"}";
                }
                task.setRepeatType(newRepeat);
                changeLog.append("周期->").append(newRepeat).append(" ");
                changed = true;
            }

            // execute_time
            if (args.has("execute_time") && !args.get("execute_time").asText().isBlank()) {
                String timeStr = args.get("execute_time").asText().strip();
                if (!timeStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                    return "{\"error\": \"execute_time 格式无效，请使用 yyyy-MM-dd HH:mm:ss\"}";
                }
                LocalDateTime newTime;
                try {
                    newTime = LocalDateTime.parse(timeStr, DTF);
                } catch (DateTimeParseException e) {
                    return "{\"error\": \"execute_time 格式无效: " + timeStr + "\"}";
                }
                if (newTime.isBefore(LocalDateTime.now())) {
                    return "{\"error\": \"执行时间不能早于当前时间\"}";
                }
                task.setExecuteTime(newTime);
                changeLog.append("时间->").append(timeStr).append(" ");
                changed = true;
            }

            if (!changed) {
                return "{\"error\": \"未提供要修改的字段，请指定 content/execute_time/repeat_type 至少一项\"}";
            }

            // === 同步 next_execute_time ===
            // 如果修改了 execute_time 或 repeat_type，同步更新 next_execute_time
            task.setNextExecuteTime(task.getExecuteTime());

            // === 保存 ===
            boolean updated = taskRepository.updateTask(task);
            if (!updated) {
                return "{\"error\": \"修改任务失败，请稍后重试\"}";
            }

            log.info("定时任务已修改 | id={} | userId={} | changes={}", taskId, userId, changeLog.toString().strip());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "updated");
            result.put("task_id", taskId);
            result.put("content", task.getContent());
            result.put("execute_time", task.getExecuteTimeAsString());
            result.put("repeat_type", task.getRepeatType());
            result.put("message", "提醒「" + task.getContent() + "」已修改（" + changeLog.toString().strip() + "）。");

            return result.toString();

        } catch (Exception e) {
            log.error("修改定时任务失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"修改定时任务失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}