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
 * 恢复 Agent 任务 LLM Function
 *
 * <p>注册名称：{@code resume_agent_task}
 *
 * <p>将 PAUSED 状态的 Agent 任务恢复为 ACTIVE 状态。
 * 恢复后调度器将重新扫描该任务并按时执行。
 * 仅支持 Agent 类型任务。
 */
@Component
public class ResumeAgentTaskTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ResumeAgentTaskTool.class);

    private final ScheduledTaskRepository taskRepository;

    public ResumeAgentTaskTool(ObjectMapper objectMapper,
                                   ToolRegistry functionRegistry,
                                   ScheduledTaskRepository taskRepository) {
        super(functionRegistry, objectMapper);
        this.taskRepository = taskRepository;
    }

    @Override
    public String getName() {
        return "resume_agent_task";
    }

    @Override
    public String getDescription() {
        return "恢复已暂停的 Agent 主动任务。\n"
                + "当用户要求「恢复这个任务」「重新开始执行」「取消暂停」时调用。\n"
                + "使用步骤：\n"
                + "1. 先调用 list_agent_tasks 找到已暂停的任务，获取 task_id\n"
                + "2. 再调用此函数传入 task_id\n\n"
                + "注意：\n"
                + "- 只能恢复 PAUSED 状态的 Agent 任务\n"
                + "- 恢复后任务变为 ACTIVE，调度器将按计划继续执行";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("task_id", "要恢复的 Agent 任务 ID。从 list_agent_tasks 的结果中获取。", true)
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
                log.warn("用户尝试恢复非自己的任务 | userId={} | taskUserId={} | taskId={}",
                        userId, task.getUserId(), taskId);
                return "{\"error\": \"无权操作该任务\"}";
            }

            // 3. 校验任务类型：仅支持 AGENT
            if (!task.isAgentTask()) {
                return "{\"error\": \"只能恢复 Agent 任务\"}";
            }

            // 4. 校验状态：仅 PAUSED 可以恢复
            if (!ScheduledTask.STATUS_PAUSED.equals(task.getStatus())) {
                return "{\"error\": \"任务状态为「" + task.getStatusDisplay()
                        + "」，仅可恢复已暂停（PAUSED）的 Agent 任务\"}";
            }

            // 5. 执行恢复
            boolean resumed = taskRepository.markResumed(taskId, userId);
            if (!resumed) {
                return "{\"error\": \"恢复任务失败，请稍后重试\"}";
            }

            log.info("Agent 任务已恢复 | id={} | userId={} | content={}",
                    taskId, userId, task.getContent());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "resumed");
            result.put("task_id", taskId);
            result.put("content", task.getContent());
            result.put("previous_status", ScheduledTask.STATUS_PAUSED);
            result.put("current_status", ScheduledTask.STATUS_ACTIVE);
            result.put("next_execute_time", task.getNextExecuteTimeAsString());
            result.put("message", "Agent 任务「" + task.getContent() + "」已恢复，将继续按计划执行。");

            return result.toString();

        } catch (Exception e) {
            log.error("恢复 Agent 任务失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"恢复 Agent 任务失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}