package com.youkeda.exercise.claw.tool.task;
import com.youkeda.exercise.claw.feature.task.service.TaskCreator;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 创建定时任务 LLM Function
 *
 * <p>注册名称：{@code create_schedule_task}
 *
 * <p>底层复用 {@link TaskCreator} 实现。
 * 支持相对时间（delay_minutes）和绝对时间（execute_time），以及周期任务（repeat_type）。
 */
@Component
public class CreateScheduleTaskTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CreateScheduleTaskTool.class);

    private final TaskCreator taskCreator;
    private final UserProfileRepository profiles;
    private final LongTermMemoryService memoryService;

    public CreateScheduleTaskTool(ObjectMapper objectMapper,
                                      ToolRegistry functionRegistry,
                                      TaskCreator taskCreator,
                                      UserProfileRepository profiles,
                                      LongTermMemoryService memoryService) {
        super(functionRegistry, objectMapper);
        this.taskCreator = taskCreator;
        this.profiles = profiles;
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "create_schedule_task";
    }

    @Override
    public String getDescription() {
        return "创建定时提醒任务。当用户要求在未来某个时间提醒做某事时调用此函数。\n"
                + "支持两种时间指定方式：\n"
                + "1. delay_minutes（相对时间）：「10分钟后提醒我提交代码」→ delay_minutes=10\n"
                + "2. execute_time（绝对时间）：「明天上午9点提醒我开会」→ execute_time=2026-07-29 09:00:00\n\n"
                + "支持周期任务（可选参数 repeat_type）：\n"
                + "- 「每天早上8点提醒我喝水」→ execute_time 为首个时间, repeat_type=DAILY\n"
                + "- 「每周一提醒我写周报」→ execute_time 为下周一, repeat_type=WEEKLY\n\n"
                + "支持 Agent 任务（可选参数 task_type）：\n"
                + "- 「每天早上8点帮我总结AI新闻」→ task_type=AGENT，调度器到时间会调用 AI 自动执行并返回结果\n"
                + "- 默认 task_type=REMINDER，即普通文字提醒\n\n"
                + "说明：delay_minutes 和 execute_time 二选一提供即可。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode delayMinutes = objectMapper.createObjectNode();
        delayMinutes.put("type", "integer");
        delayMinutes.put("description", "【相对时间】从现在起多少分钟后执行，与 execute_time 二选一。范围 1~43200（30天）。");
        delayMinutes.put("minimum", 1);
        delayMinutes.put("maximum", 43200);

        ObjectNode repeatType = objectMapper.createObjectNode();
        repeatType.put("type", "string");
        repeatType.put("description", "【可选】重复类型。ONCE=一次性(默认), DAILY=每天, WEEKLY=每周。");
        repeatType.putArray("enum").add("NONE").add("ONCE").add("DAILY").add("WEEKLY").add("MONTHLY");

        ObjectNode taskType = objectMapper.createObjectNode();
        taskType.put("type", "string");
        taskType.put("description", "【可选】任务类型。REMINDER=普通提醒(默认), AGENT=Agent 自动执行任务（到时间后 AI 自动执行并返回结果）。");
        taskType.putArray("enum").add("REMINDER").add("AGENT");

        return schema()
                .string("content", "提醒内容，如「提交代码」「开会」「喝水」。简洁明确，不宜过长。", true)
                .raw("delay_minutes", delayMinutes, false)
                .string("execute_time", "【绝对时间】首次执行时间，格式 yyyy-MM-dd HH:mm:ss，如 2026-07-29 09:00:00。与 delay_minutes 二选一。", false)
                .raw("repeat_type", repeatType, false)
                .raw("task_type", taskType, false)
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

            // 邮箱校验：若开启了邮件提醒但用户未提供邮箱，提示先告知邮箱
            if (profiles.emailNotificationsEnabled(userId)) {
                String email = profiles.getEmail(userId);
                if (email == null || email.isBlank()) {
                    email = memoryService.findEmailAddress(userId);
                }
                if (email == null || email.isBlank()) {
                    return "{\"status\": \"NEED_MORE_INFORMATION\", \"message\": \"创建提醒需要你的邮箱地址来发送通知，但你还没有告诉我。请先把你的邮箱告诉我，我会立刻帮你创建提醒。\"}";
                }
            }

            // 委托 TaskCreator 创建
            TaskCreator.CreateResult result = taskCreator.createFromNode(userId, args);

            if (!result.isSuccess()) {
                return "{\"error\": \"" + result.getErrorMessage() + "\"}";
            }

            ScheduledTask task = result.getTask();
            String timeDesc = result.getDelayMinutes() != null
                    ? result.getDelayMinutes() + " 分钟后（" + task.getExecuteTimeAsString() + "）"
                    : task.getExecuteTimeAsString();

            String repeatDesc = switch (task.getRepeatType()) {
                case ScheduledTask.REPEAT_TYPE_DAILY -> "（每天重复）";
                case ScheduledTask.REPEAT_TYPE_WEEKLY -> "（每周重复）";
                case ScheduledTask.REPEAT_TYPE_MONTHLY -> "（每月重复）";
                default -> "";
            };

            String taskTypeDesc = task.isAgentTask() ? " [Agent任务]" : "";

            ObjectNode res = objectMapper.createObjectNode();
            res.put("status", "SUCCESS");
            res.put("task_id", task.getId());
            res.put("content", task.getContent());
            res.put("execute_time", task.getExecuteTimeAsString());
            res.put("repeat_type", task.getRepeatType());
            res.put("task_type", task.getTaskType());
            if (result.getDelayMinutes() != null) {
                res.put("delay_minutes", result.getDelayMinutes());
            }
            res.put("message", "定时任务已创建，将在 " + timeDesc + " " + task.getTaskTypeDisplay()
                    + "：" + task.getContent() + repeatDesc + taskTypeDesc);

            return res.toString();

        } catch (Exception e) {
            log.error("创建定时任务失败 | args={} | error={}", argumentsJson, e.getMessage(), e);
            return "{\"error\": \"创建定时任务失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}