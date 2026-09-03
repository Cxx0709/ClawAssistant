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

/**
 * 取消定时任务 LLM Function
 *
 * <p>注册名称：{@code cancel_schedule_task}
 *
 * <p>LLM 在用户要求取消提醒时调用此函数。
 * 通过任务 ID 取消指定的定时任务，仅允许取消属于自己的任务。
 *
 * <p>取消后任务状态变为 CANCELLED，数据库保留记录不删除。
 */
@Component
public class CancelScheduleTaskTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CancelScheduleTaskTool.class);

    private final ScheduledTaskRepository taskRepository;

    public CancelScheduleTaskTool(ObjectMapper objectMapper,
                                      ToolRegistry functionRegistry,
                                      ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "cancel_schedule_task";
    }

    @Override
    public String getDescription() {
        return "取消指定的定时提醒任务。\n"
                + "当用户要求取消某个提醒时调用。\n"
                + "场景举例：\n"
                + "- 「取消我的提醒」— 应先调用 list_schedule_tasks 获取任务列表，让用户确认要取消哪个\n"
                + "- 「取消下午3点的提醒」— 先调用 list_schedule_tasks 找到对应任务，获取 task_id 后调用此函数\n"
                + "注意：只能取消 ACTIVE 状态的任务。已执行或已取消的任务无法再次取消。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("task_id", "要取消的定时任务 ID。从 list_schedule_tasks 的返回结果中获取。", true)
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

            // 校验 task_id 参数
            if (!args.has("task_id") || !args.get("task_id").canConvertToExactIntegral()) {
                return "{\"error\": \"缺少或无效的 task_id\"}";
            }

            long taskId = args.get("task_id").asLong();

            // 先查询任务是否存在
            ScheduledTask task = taskRepository.findById(taskId);
            if (task == null) {
                return "{\"error\": \"未找到 ID 为 " + taskId + " 的定时任务\"}";
            }

            // 校验归属：只能取消自己的任务
            if (!userId.equals(task.getUserId())) {
                log.warn("用户尝试取消非自己的任务 | userId={} | taskUserId={} | taskId={}",
                        userId, task.getUserId(), taskId);
                return "{\"error\": \"无权取消该任务\"}";
            }

            // 校验状态：只能取消 ACTIVE 的任务
            if (!ScheduledTask.STATUS_ACTIVE.equals(task.getStatus())) {
                return "{\"error\": \"任务状态为「" + task.getStatusDisplay()
                        + "」，无法取消（仅可取消待执行的任务）\"}";
            }

            // 执行取消（带 userId 归属校验）
            boolean cancelled = taskRepository.markCancelled(taskId, userId);
            if (!cancelled) {
                return "{\"error\": \"取消任务失败，请稍后重试\"}";
            }

            log.info("定时任务已取消 | id={} | userId={} | content={} | executeTime={}",
                    taskId, userId, task.getContent(), task.getExecuteTimeAsString());

            // 构建返回结果
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "cancelled");
            result.put("task_id", taskId);
            result.put("content", task.getContent());
            result.put("execute_time", task.getExecuteTimeAsString());
            result.put("message", "提醒「" + task.getContent() + "」已取消。");

            return result.toString();

        } catch (Exception e) {
            log.error("取消定时任务失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"取消定时任务失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}