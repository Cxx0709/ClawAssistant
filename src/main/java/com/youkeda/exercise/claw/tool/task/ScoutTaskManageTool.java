package com.youkeda.exercise.claw.tool.task;
import com.youkeda.exercise.claw.feature.scout.ScoutTaskManager;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ScoutTaskManageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ScoutTaskManageTool.class);

    private final ScoutTaskManager taskManager;

    public ScoutTaskManageTool(ToolRegistry registry,
                                   ScoutTaskManager taskManager,
                                   ObjectMapper objectMapper) {
        super(registry, objectMapper);
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "scout_task_manage";
    }

    @Override
    public String getDescription() {
        return "管理信息猎手任务：查看状态、取消、重试、列出所有任务";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.putArray("enum").add("status").add("cancel").add("list").add("retry");
        action.put("description", "操作类型");

        return schema()
                .raw("action", action, true)
                .string("taskId", "任务ID（status/cancel/retry时需要）", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = args.get("action").asText();
            return switch (action) {
                case "status" -> handleStatus(args);
                case "cancel" -> handleCancel(args);
                case "list" -> handleList(args);
                case "retry" -> "{\"status\":\"retry not yet implemented\"}";
                default -> "{\"error\":\"unknown action: " + action + "\"}";
            };
        } catch (Exception e) {
            log.error("scout_task_manage failed", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String handleStatus(JsonNode args) {
        if (!args.has("taskId")) return "{\"error\":\"taskId is required\"}";
        return taskManager.getTask(args.get("taskId").asText())
                .map(t -> String.format(
                    "{\"taskId\":\"%s\",\"status\":\"%s\",\"query\":\"%s\",\"summary\":\"%s\"}",
                    t.taskId(), t.status().name(),
                    t.query() != null ? t.query() : "",
                    t.summary() != null ? t.summary() : ""))
                .orElse("{\"error\":\"task not found\"}");
    }

    private String handleCancel(JsonNode args) {
        if (!args.has("taskId")) return "{\"error\":\"taskId is required\"}";
        return taskManager.cancelTask(args.get("taskId").asText())
                ? "{\"status\":\"cancelled\"}"
                : "{\"error\":\"task not found or not running\"}";
    }

    private String handleList(JsonNode args) {
        List<ScoutTask> tasks = taskManager.listTasks();
        String tasksJson = tasks.stream()
                .map(t -> String.format(
                    "{\"taskId\":\"%s\",\"query\":\"%s\",\"status\":\"%s\"}",
                    t.taskId(), t.query() != null ? t.query() : "", t.status().name()))
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"tasks\":" + tasksJson + "}";
    }

}
