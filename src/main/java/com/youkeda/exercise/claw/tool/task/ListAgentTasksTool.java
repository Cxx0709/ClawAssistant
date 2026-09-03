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
 * 查询 Agent 任务列表 LLM Function
 *
 * <p>注册名称：{@code list_agent_tasks}
 *
 * <p>专门查询 {@link ScheduledTask#TASK_TYPE_AGENT} 类型的任务。
 * 当用户问「我的主动任务」「我的 Agent 任务」「自动任务有哪些」等时调用。
 * 支持按状态筛选：ACTIVE=待执行, PAUSED=已暂停, DONE=已完成。
 */
@Component
public class ListAgentTasksTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ListAgentTasksTool.class);

    private static final int MAX_TASKS = 50;

    private final ScheduledTaskRepository taskRepository;

    public ListAgentTasksTool(ObjectMapper objectMapper,
                                  ToolRegistry functionRegistry,
                                  ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "list_agent_tasks";
    }

    @Override
    public String getDescription() {
        return "查询当前用户的 Agent 主动任务列表（自动执行的 AI 任务）。\n"
                + "当用户问「我的主动任务」「Auto任务」「自动任务有哪些」「Agent 任务」等时调用。\n"
                + "可通过 status 参数筛选：ACTIVE=待执行, RUNNING=执行中, PAUSED=已暂停, DONE=已完成, CANCELLED=已取消, FAILED=执行失败。\n"
                + "不带 status 参数时返回用户全部 Agent 任务。\n"
                + "注意：普通提醒任务请用 list_schedule_tasks 查询。";
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

            // 查询 Agent 任务
            List<ScheduledTask> tasks;
            if (statusFilter != null) {
                tasks = taskRepository.findByUserIdAndTypeAndStatus(userId,
                        ScheduledTask.TASK_TYPE_AGENT, statusFilter);
            } else {
                tasks = taskRepository.findByUserIdAndType(userId, ScheduledTask.TASK_TYPE_AGENT);
            }

            log.info("查询 Agent 任务列表 | userId={} | statusFilter={} | count={}",
                    userId, statusFilter != null ? statusFilter : "ALL", tasks.size());

            // 构建返回结果
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ok");
            result.put("user_id", userId);
            result.put("total_count", tasks.size());
            result.put("status_filter", statusFilter != null ? statusFilter : "ALL");

            if (tasks.isEmpty()) {
                if (statusFilter != null) {
                    result.put("message", "没有" + statusDisplay(statusFilter) + "的 Agent 任务。");
                } else {
                    result.put("message", "当前没有任何 Agent 任务。");
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
                    taskNode.put("repeat_type", task.getRepeatType());
                    taskNode.put("repeat_label", task.getRepeatTypeDisplay());
                    taskNode.put("task_type", task.getTaskType());
                    taskNode.put("task_type_label", task.getTaskTypeDisplay());
                    taskNode.put("status", task.getStatus());
                    taskNode.put("status_label", task.getStatusDisplay());
                    count++;
                }

                String summary = "共有 " + tasks.size() + " 个 Agent 任务";
                if (tasks.size() > MAX_TASKS) {
                    summary += "（显示前 " + MAX_TASKS + " 条）";
                }
                result.put("message", summary);
            }

            return result.toString();

        } catch (Exception e) {
            log.error("查询 Agent 任务列表失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"查询 Agent 任务列表失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String statusDisplay(String status) {
        if (status == null) return "";
        return switch (status) {
            case ScheduledTask.STATUS_ACTIVE -> "待执行";
            case ScheduledTask.STATUS_PAUSED -> "已暂停";
            case ScheduledTask.STATUS_DONE -> "已完成";
            case ScheduledTask.STATUS_CANCELLED -> "已取消";
            case ScheduledTask.STATUS_FAILED -> "执行失败";
            default -> status;
        };
    }
}