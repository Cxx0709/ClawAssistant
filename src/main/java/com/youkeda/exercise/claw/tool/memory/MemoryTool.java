package com.youkeda.exercise.claw.tool.memory;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryCategory;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆管理工具（LLM Function Calling）
 *
 * 让用户通过自然语言管理长期记忆：
 * - "记住我喜欢吃辣" → save
 * - "你记住了我的什么" → list
 * - "删除关于生日的记忆" → delete
 * - "我之前让你记住的偏好有哪些" → recall（按分类查询）
 */
@Component
public class MemoryTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final LongTermMemoryService memoryService;

    public MemoryTool(ObjectMapper objectMapper,
                          ToolRegistry functionRegistry,
                          LongTermMemoryService memoryService) {
        super(functionRegistry, objectMapper);
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "memory_manage";
    }

    @Override
    public String getDescription() {
        return "管理用户的长期记忆。支持四种操作：\n" +
                "1. save — 保存一条记忆。当用户说\"记住XX\"、\"帮我记一下XX\"时调用\n" +
                "2. list — 列出用户的全部记忆。当用户问\"你记住了什么\"、\"我的记忆有哪些\"时调用\n" +
                "3. recall — 按分类查询记忆。当用户问\"我的偏好有哪些\"、\"我的规则是什么\"时调用\n" +
                "4. delete — 删除一条记忆。当用户说\"忘掉XX\"、\"删除关于XX的记忆\"时调用";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：save=保存记忆, list=列出全部, recall=按分类查询, delete=删除");
        action.putArray("enum").add("save").add("list").add("recall").add("delete");

        ObjectNode category = objectMapper.createObjectNode();
        category.put("type", "string");
        category.put("description", "记忆分类（save/recall 时可选）：PREFERENCE=偏好, RULE=规则, FACT=事实, GOAL=目标, EXPERIENCE=经验");
        category.putArray("enum")
                .add("PREFERENCE").add("RULE").add("FACT").add("GOAL").add("EXPERIENCE");

        return schema()
                .raw("action", action, true)
                .string("content", "记忆内容（save 时必填，delete 时填写要删除的记忆描述）", false)
                .raw("category", category, false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String actionStr = args.path("action").asText("");

            log.info("MemoryTool 执行 | action={} | args={}",
                    actionStr, argumentsJson);

            return switch (actionStr) {
                case "save" -> handleSave(args);
                case "list" -> handleList();
                case "recall" -> handleRecall(args);
                case "delete" -> handleDelete(args);
                default -> errorJson("不支持的 action: " + actionStr);
            };
        } catch (Exception e) {
            log.error("MemoryTool 执行失败 | args={}", argumentsJson, e);
            return errorJson(e.getMessage());
        }
    }

    // ==================== Action 处理 ====================

    private String handleSave(JsonNode args) {
        String content = args.path("content").asText("");
        if (content.isBlank()) {
            return errorJson("save 操作需要 content 参数");
        }

        MemoryCategory category;
        try {
            category = MemoryCategory.valueOf(
                    args.path("category").asText("PREFERENCE").toUpperCase());
        } catch (IllegalArgumentException e) {
            return errorJson("无效的分类: " + args.path("category").asText());
        }

        boolean saved = memoryService.saveManual(category, content);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "save");
        result.put("success", saved);
        result.put("content", content);
        result.put("category", category.name());
        result.put("message", saved ? "已记住：" + content : "记忆保存失败，请稍后重试");
        return result.toString();
    }

    private String handleList() {
        List<MemoryItem> memories = memoryService.listAll();
        return buildMemoryListResult("list", memories);
    }

    private String handleRecall(JsonNode args) {
        String categoryStr = args.path("category").asText("");
        if (categoryStr.isBlank()) {
            // 无分类过滤，返回全部
            return handleList();
        }

        try {
            MemoryCategory category = MemoryCategory.valueOf(categoryStr.toUpperCase());
            List<MemoryItem> all = memoryService.listAll();
            List<MemoryItem> filtered = all.stream()
                    .filter(m -> !m.disabled())
                    .filter(m -> m.category() == category)
                    .toList();
            return buildMemoryListResult("recall", filtered);
        } catch (IllegalArgumentException e) {
            return errorJson("无效的分类: " + categoryStr);
        }
    }

    private String handleDelete(JsonNode args) {
        String content = args.path("content").asText("");
        if (content.isBlank()) {
            return errorJson("delete 操作需要 content 参数描述要删除的记忆");
        }

        // 通过语义检索找到最匹配的记忆
        List<MemoryItem> all = memoryService.listAll();
        MemoryItem target = all.stream()
                .filter(m -> m.content().contains(content) || content.contains(m.content()))
                .findFirst()
                .orElse(null);

        if (target == null) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("action", "delete");
            result.put("success", false);
            result.put("message", "未找到与\"" + content + "\"匹配的记忆");
            return result.toString();
        }

        boolean deleted = memoryService.delete(target.id());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "delete");
        result.put("success", deleted);
        if (deleted) {
            result.put("deleted_content", target.content());
        }
        result.put("message", deleted
                ? "已删除记忆：" + target.content()
                : "记忆删除失败或已不存在");
        return result.toString();
    }

    // ==================== 工具方法 ====================

    private String buildMemoryListResult(String action, List<MemoryItem> memories) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", action);
        result.put("count", memories.size());

        if (memories.isEmpty()) {
            result.put("message", "目前没有任何长期记忆");
        } else {
            var array = result.putArray("memories");
            for (MemoryItem item : memories) {
                ObjectNode mem = array.addObject();
                mem.put("id", item.id());
                mem.put("category", item.category().name());
                mem.put("topic_key", item.topicKey());
                mem.put("content", item.content());
                mem.put("importance", item.importance());
                mem.put("confidence", item.confidence());
                mem.put("source", item.source().name());
                mem.put("disabled", item.disabled());
            }
            result.put("message", "共有 " + memories.size() + " 条记忆");
        }
        return result.toString();
    }

    private String errorJson(String message) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("error", message);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
