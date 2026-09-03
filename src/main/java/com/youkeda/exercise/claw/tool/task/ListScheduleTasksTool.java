package com.youkeda.exercise.claw.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询定时任务列表 LLM Function
 *
 * <p>注册名称：{@code list_schedule_tasks}
 *
 * <p>LLM 在用户询问「我的提醒有哪些」「待办提醒」「已完成的任务」等时调用此函数。
 * 返回当前用户的定时任务列表，支持按状态筛选。
 */
@Component
public class ListScheduleTasksTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ListScheduleTasksTool.class);

    private static final int MAX_TASKS = 50;

    private final ScheduledTaskRepository taskRepository;

    public ListScheduleTasksTool(ObjectMapper objectMapper,
                                     ToolRegistry functionRegistry,
                                     ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "list_schedule_tasks";
    }

    @Override
    public String getDescription() {
        return "查询当前用户的定时提醒任务列表。\n"
                + "当用户问「我的提醒有哪些」「待办提醒」「已完成的提醒」「我的任务」等时调用。\n"
                + "可通过 status 参数筛选：ACTIVE=待执行, RUNNING=执行中, PAUSED=已暂停, DONE=已完成, CANCELLED=已取消。\n"
                + "不带 status 参数时返回用户全部任务。\n"
                + "每个任务返回两个时间字段：execute_time 是配置的基准时间（周期任务可能已过期），"
                + "next_execute_time 才是真正的下次触发时间。回答「下次提醒」「什么时候提醒」时必须用 next_execute_time。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("type", "string");
        status.put("description", "筛选条件（可选）：ACTIVE=待执行, RUNNING=执行中, PAUSED=已暂停, DONE=已完成, CANCELLED=已取消, FAILED=执行失败。不传则返回全部。");
        status.putArray("enum").add("ACTIVE").add("RUNNING").add("PAUSED").add("DONE").add("CANCELLED").add("FAILED");

        return schema()
                .raw("status", status, false)
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

            // 解析可选的 status 筛选
            String statusFilter = args.has("status") ? args.get("status").asText().strip() : null;
            if (statusFilter != null && statusFilter.isEmpty()) {
                statusFilter = null;
            }

            // 查询任务
            List<ScheduledTask> tasks;
            if (statusFilter != null) {
                tasks = taskRepository.findByUserIdAndStatus(userId, statusFilter);
            } else {
                tasks = taskRepository.findByUserId(userId);
            }

            log.info("查询定时任务列表 | userId={} | statusFilter={} | count={}",
                    userId, statusFilter != null ? statusFilter : "ALL", tasks.size());

            // 构建返回结果
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("user_id", userId);
            result.put("total_count", tasks.size());
            result.put("status_filter", statusFilter != null ? statusFilter : "ALL");

            if (tasks.isEmpty()) {
                if (statusFilter != null) {
                    result.put("message", "没有" + statusDisplay(statusFilter) + "的提醒任务。");
                } else {
                    result.put("message", "当前没有任何提醒任务。");
                }
            } else {
                ArrayNode tasksArray = result.putArray("tasks");
                int count = 0;
                for (ScheduledTask task : tasks) {
                    if (count >= MAX_TASKS) break;
                    ObjectNode taskNode = tasksArray.addObject();
                    taskNode.put("id", task.getId());
                    taskNode.put("content", task.getContent());
                    taskNode.put("execute_time", task.getExecuteTimeAsString());
                    taskNode.put("next_execute_time", task.getNextExecuteTimeAsString());
                    taskNode.put("repeat_type", task.getRepeatType());
                    taskNode.put("repeat_label", task.getRepeatTypeDisplay());
                    taskNode.put("task_type", task.getTaskType());
                    taskNode.put("task_type_label", task.getTaskTypeDisplay());
                    taskNode.put("status", task.getStatus());
                    taskNode.put("status_label", task.getStatusDisplay());
                    count++;
                }

                String summary = "共有 " + tasks.size() + " 条提醒";
                if (tasks.size() > MAX_TASKS) {
                    summary += "（显示前 " + MAX_TASKS + " 条）";
                }
                result.put("message", summary);
            }

            return result.toString();

        } catch (Exception e) {
            log.error("查询定时任务列表失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"查询定时任务列表失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 状态代码 → 中文描述
     */
    private static String statusDisplay(String status) {
        if (status == null) return "";
        return switch (status) {
            case ScheduledTask.STATUS_ACTIVE -> "待执行";
            case ScheduledTask.STATUS_RUNNING -> "执行中";
            case ScheduledTask.STATUS_PAUSED -> "已暂停";
            case ScheduledTask.STATUS_DONE -> "已完成";
            case ScheduledTask.STATUS_CANCELLED -> "已取消";
            case ScheduledTask.STATUS_FAILED -> "执行失败";
            default -> status;
        };
    }
}