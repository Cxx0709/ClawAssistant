package com.youkeda.exercise.claw.tool.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.goal.GrowthGoal;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 通过 Function Calling 创建和管理长期成长目标。 */
@Component
public class GoalManageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GoalManageTool.class);

    private final GrowthGoalService service;

    public GoalManageTool(ObjectMapper objectMapper,
                          ToolRegistry registry,
                          GrowthGoalService service) {
        super(registry, objectMapper);
        this.service = service;
    }

    @Override
    public String getName() {
        return "goal_manage";
    }

    @Override
    public String getDescription() {
        return "管理用户的长期成长目标。支持操作：\n"
                + "1. create — 创建目标。需要 title，可选 success_criteria、deadline\n"
                + "2. list — 列出用户目标。可选 status 过滤（ACTIVE/COMPLETED/CANCELLED）\n"
                + "3. update — 更新目标进度。需要 goal_id，可选 progress(0-100)、evidence\n"
                + "4. complete — 标记目标完成。需要 goal_id\n"
                + "5. cancel — 取消目标。需要 goal_id";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型");
        action.putArray("enum").add("create").add("list").add("update").add("complete").add("cancel");

        ObjectNode status = objectMapper.createObjectNode();
        status.put("type", "string");
        status.put("description", "目标状态过滤，仅 list 时使用");
        status.putArray("enum").add("ACTIVE").add("COMPLETED").add("CANCELLED");

        ObjectNode progress = objectMapper.createObjectNode();
        progress.put("type", "integer");
        progress.put("description", "目标进度百分比 0-100，仅 update 时使用");
        progress.put("minimum", 0);
        progress.put("maximum", 100);

        return schema()
                .raw("action", action, true)
                .string("goal_id", "目标ID，update/complete/cancel 时必填", false)
                .string("title", "目标标题，create 时必填", false)
                .string("success_criteria", "成功标准，create/update 时可选", false)
                .string("deadline", "截止日期 yyyy-MM-dd，create/update 时可选", false)
                .raw("status", status, false)
                .raw("progress", progress, false)
                .string("evidence", "进展证据，update 时可选", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = args.path("action").asText("").toLowerCase();
            String userId = context.userId();

            return switch (action) {
                case "create" -> handleCreate(args, userId);
                case "list" -> handleList(args, userId);
                case "update" -> handleUpdate(args, userId);
                case "complete" -> handleComplete(args, userId);
                case "cancel" -> handleCancel(args, userId);
                default -> error("不支持的 action: " + action);
            };
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.error("成长目标工具执行失败 | args={}", argumentsJson, e);
            return error("成长目标操作失败");
        }
    }

    private String handleCreate(JsonNode args, String userId) {
        GrowthGoal goal = service.create(
                userId,
                args.path("title").asText(""),
                textOrNull(args, "success_criteria"),
                textOrNull(args, "deadline"));

        ObjectNode result = success("create");
        result.set("goal", toJson(goal));
        result.put("message", "成长目标已创建，goal_id=" + goal.id());
        return result.toString();
    }

    private String handleList(JsonNode args, String userId) {
        GrowthGoal.Status statusFilter = null;
        String statusStr = args.path("status").asText("");
        if (!statusStr.isBlank()) {
            try {
                statusFilter = GrowthGoal.Status.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("无效的状态: " + statusStr);
            }
        }

        List<GrowthGoal> goals = service.list(userId, statusFilter);

        ObjectNode result = success("list");
        result.put("count", goals.size());
        ArrayNode arr = result.putArray("goals");
        for (GrowthGoal g : goals) {
            arr.add(toJson(g));
        }
        result.put("message", goals.isEmpty() ? "暂无目标" : "共有 " + goals.size() + " 个目标");
        return result.toString();
    }

    private String handleUpdate(JsonNode args, String userId) {
        long goalId = parseGoalId(args);
        Integer progress = args.has("progress") ? args.path("progress").asInt(-1) : null;
        if (progress != null && progress == -1) progress = null;

        GrowthGoal goal = service.update(userId, goalId,
                textOrNull(args, "title"),
                textOrNull(args, "success_criteria"),
                textOrNull(args, "deadline"),
                progress,
                textOrNull(args, "evidence"));

        ObjectNode result = success("update");
        result.set("goal", toJson(goal));
        result.put("message", "目标已更新，goal_id=" + goal.id());
        return result.toString();
    }

    private String handleComplete(JsonNode args, String userId) {
        long goalId = parseGoalId(args);
        GrowthGoal goal = service.complete(userId, goalId);

        ObjectNode result = success("complete");
        result.set("goal", toJson(goal));
        result.put("message", "目标已完成，goal_id=" + goal.id());
        return result.toString();
    }

    private String handleCancel(JsonNode args, String userId) {
        long goalId = parseGoalId(args);
        boolean cancelled = service.cancel(userId, goalId);

        ObjectNode result = success("cancel");
        result.put("cancelled", cancelled);
        result.put("goal_id", goalId);
        result.put("message", cancelled ? "目标已取消，goal_id=" + goalId : "目标取消失败或不存在");
        return result.toString();
    }

    private long parseGoalId(JsonNode args) {
        String goalIdStr = args.path("goal_id").asText("");
        if (goalIdStr.isBlank()) {
            throw new IllegalArgumentException("goal_id 不能为空");
        }
        try {
            return Long.parseLong(goalIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("goal_id 必须是数字");
        }
    }

    private ObjectNode success(String action) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "SUCCESS");
        node.put("action", action);
        return node;
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message == null ? "未知错误" : message);
        return node.toString();
    }

    private ObjectNode toJson(GrowthGoal goal) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", goal.id());
        node.put("title", goal.title());
        node.put("success_criteria", goal.successCriteria());
        if (goal.deadline() == null) node.putNull("deadline");
        else node.put("deadline", goal.deadline());
        node.put("status", goal.status().name());
        node.put("progress", goal.progress());
        if (goal.latestEvidence() != null && !goal.latestEvidence().isEmpty()) {
            node.put("latest_evidence", goal.latestEvidence());
        }
        return node;
    }

    private static String textOrNull(JsonNode args, String field) {
        JsonNode value = args.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
