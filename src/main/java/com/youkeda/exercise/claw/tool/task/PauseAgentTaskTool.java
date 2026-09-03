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
 * 暂停 Agent 任务 LLM Function
 *
 * <p>注册名称：{@code pause_agent_task}
 *
 * <p>将 ACTIVE 状态的 Agent 任务暂停为 PAUSED 状态。
 * 暂停后调度器将跳过该任务（因查询条件为 status='ACTIVE'）。
 * 仅支持 Agent 类型任务，不支持普通提醒任务。
 * 恢复请调用 {@link ResumeAgentTaskFunction}。
 */
@Component
public class PauseAgentTaskTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(PauseAgentTaskTool.class);

    private final ScheduledTaskRepository taskRepository;

    public PauseAgentTaskTool(ObjectMapper objectMapper,
                                  ToolRegistry functionRegistry,
                                  ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "pause_agent_task";
    }

    @Override
    public String getDescription() {
        return "暂停指定的 Agent 主动任务。\n"
                + "当用户要求「暂停这个任务」「先别执行了」「停掉自动任务」时调用。\n"
                + "使用步骤：\n"
                + "1. 先调用 list_agent_tasks 获取任务列表，找到要暂停的任务 ID\n"
                + "2. 再调用此函数传入 task_id\n\n"
                + "注意：\n"
                + "- 只能暂停 AGENT 类型的任务\n"
                + "- 只能暂停 ACTIVE 状态的任务\n"
                + "- 暂停后任务变为 PAUSED，调度器将自动跳过\n"
                + "- 普通提醒任务不支持暂停（请用 cancel_schedule_task）\n"
                + "- 如需恢复请调用 resume_agent_task";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("task_id", "要暂停的 Agent 任务 ID。从 list_agent_tasks 的结果中获取。", true)
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

            // 1. 查询任务
            ScheduledTask task = taskRepository.findById(taskId);
            if (task == null) {
                return "{\"error\": \"未找到 ID 为 " + taskId + " 的定时任务\"}";
            }

            // 2. 校验归属
            if (!userId.equals(task.getUserId())) {
                log.warn("用户尝试暂停非自己的任务 | userId={} | taskUserId={} | taskId={}",
                        userId, task.getUserId(), taskId);
                return "{\"error\": \"无权操作该任务\"}";
            }

            // 3. 校验任务类型：仅支持 AGENT
            if (!task.isAgentTask()) {
                return "{\"error\": \"只能暂停 Agent 任务，普通提醒任务请使用 cancel_schedule_task 取消\"}";
            }

            // 4. 校验状态：仅 ACTIVE 可以暂停
            if (!ScheduledTask.STATUS_ACTIVE.equals(task.getStatus())) {
                return "{\"error\": \"任务状态为「" + task.getStatusDisplay()
                        + "」，仅可暂停待执行（ACTIVE）的 Agent 任务\"}";
            }

            // 5. 执行暂停
            boolean paused = taskRepository.markPaused(taskId, userId);
            if (!paused) {
                return "{\"error\": \"暂停任务失败，请稍后重试\"}";
            }

            log.info("Agent 任务已暂停 | id={} | userId={} | content={}",
                    taskId, userId, task.getContent());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "paused");
            result.put("task_id", taskId);
            result.put("content", task.getContent());
            result.put("previous_status", ScheduledTask.STATUS_ACTIVE);
            result.put("current_status", ScheduledTask.STATUS_PAUSED);
            result.put("message", "Agent 任务「" + task.getContent() + "」已暂停，将不再自动执行。");

            return result.toString();

        } catch (Exception e) {
            log.error("暂停 Agent 任务失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"暂停 Agent 任务失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}