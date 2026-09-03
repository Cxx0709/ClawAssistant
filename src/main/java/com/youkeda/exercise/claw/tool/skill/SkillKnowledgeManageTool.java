package com.youkeda.exercise.claw.tool.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.ai.retrieval.KnowledgeImportResult;
import com.youkeda.exercise.claw.ai.retrieval.KnowledgeStoreStatus;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SkillKnowledgeManageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SkillKnowledgeManageTool.class);

    private final SkillKnowledgeImportService importService;

    @Value("${skill.knowledge.management.enabled:false}")
    private boolean managementEnabled;

    @Value("${skill.knowledge.management.allowed-users:}")
    private String allowedUsers;

    public SkillKnowledgeManageTool(ToolRegistry registry,
                                    SkillKnowledgeImportService importService,
                                    ObjectMapper objectMapper) {
        super(registry, objectMapper);
        this.importService = importService;
    }

    @Override
    public String getName() {
        return "skill_knowledge_manage";
    }

    @Override
    public String getDescription() {
        return "仅在用户明确要求时管理 Skill 知识库：导入文本、软删除文档、查看真实后端状态。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");
        properties.putObject("action")
                .put("type", "string")
                .put("description", "操作类型")
                .putArray("enum")
                .add("import").add("soft_delete_document").add("status");
        properties.putObject("skillName")
                .put("type", "string")
                .put("description", "目标 Skill 名称");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "导入的文本内容；import 时必填，不接受本地路径");
        properties.putObject("source")
                .put("type", "string")
                .put("description", "文档来源标签");
        properties.putObject("contentType")
                .put("type", "string")
                .put("description", "AUTO 自动识别，默认 AUTO")
                .putArray("enum").add("AUTO").add("PLAIN_TEXT").add("MARKDOWN");
        properties.putObject("sourceVersion")
                .put("type", "string")
                .put("description", "文档来源版本，默认 1.0");
        properties.putObject("documentId")
                .put("type", "string")
                .put("description", "soft_delete_document 时必填");
        params.putArray("required").add("action").add("skillName");
        return params;
    }

    @Override
    public boolean isAvailable(ToolExecutionContext context) {
        if (!managementEnabled || context == null || context.currentMessage() == null
                || !isAllowedUser(context.userId())) return false;
        String message = context.currentMessage().toLowerCase(Locale.ROOT);
        boolean mentionsKnowledge = message.contains("知识库")
                || message.contains("skill knowledge")
                || message.contains("skill 知识");
        boolean explicitAction = message.contains("导入") || message.contains("删除")
                || message.contains("状态") || message.contains("管理")
                || message.contains("import") || message.contains("delete")
                || message.contains("status");
        return mentionsKnowledge && explicitAction;
    }

    @Override
    public String getUnavailableReason(ToolExecutionContext context) {
        return managementEnabled
                ? "只有用户明确要求导入、删除或检查 Skill 知识库时才允许使用此工具。"
                : "Skill 知识库管理工具未启用。";
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        if (!isAvailable(context)) {
            return error(getUnavailableReason(context));
        }
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = requiredText(args, "action");
            if (context != null && !isActionExplicitlyRequested(context.currentMessage(), action)) {
                return error("requested management action does not match the user's explicit intent");
            }
            String skillName = requiredText(args, "skillName");
            return switch (action) {
                case "import" -> importDocument(args, skillName);
                case "soft_delete_document" -> deleteDocument(args, skillName);
                case "status" -> status(skillName);
                default -> error("unknown action: " + action);
            };
        } catch (Exception e) {
            log.warn("skill_knowledge_manage failed | error={}", e.getMessage());
            return error(e.getMessage());
        }
    }

    private boolean isActionExplicitlyRequested(String currentMessage, String action) {
        if (currentMessage == null || action == null) return false;
        String message = currentMessage.toLowerCase(Locale.ROOT);
        return switch (action) {
            case "import" -> message.contains("导入") || message.contains("import");
            case "soft_delete_document" -> message.contains("删除") || message.contains("delete");
            case "status" -> message.contains("状态") || message.contains("status")
                    || message.contains("查看") || message.contains("检查");
            default -> false;
        };
    }

    private boolean isAllowedUser(String userId) {
        if (userId == null || userId.isBlank() || allowedUsers == null || allowedUsers.isBlank()) {
            return false;
        }
        for (String candidate : allowedUsers.split(",")) {
            if (userId.equals(candidate.trim())) return true;
        }
        return false;
    }

    private String importDocument(JsonNode args, String skillName) throws Exception {
        KnowledgeImportResult result = importService.importDocument(
                skillName,
                requiredText(args, "content"),
                optionalText(args, "source", "manual"),
                optionalText(args, "contentType",
                        optionalText(args, "format", "AUTO")),
                optionalText(args, "sourceVersion",
                        optionalText(args, "version", "1.0")));
        ObjectNode payload = objectMapper.valueToTree(result);
        payload.put("actionStatus", result.status());
        payload.put("status", "SUCCESS");
        if (result.error() == null || result.error().isBlank()) payload.remove("error");
        return objectMapper.writeValueAsString(payload);
    }

    private String deleteDocument(JsonNode args, String skillName) throws Exception {
        String documentId = requiredText(args, "documentId");
        long affected = importService.deleteDocument(skillName, documentId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCESS");
        result.put("actionStatus", affected > 0 ? "deleted" : "not_found");
        result.put("skillName", skillName);
        result.put("documentId", documentId);
        result.put("affectedChunks", affected);
        return objectMapper.writeValueAsString(result);
    }

    private String status(String skillName) throws Exception {
        KnowledgeStoreStatus storeStatus = importService.status(skillName);
        ObjectNode payload = objectMapper.valueToTree(storeStatus);
        payload.put("status", storeStatus.available() ? "SUCCESS" : "FAILED");
        if (!storeStatus.available()) payload.put("error", storeStatus.message());
        return objectMapper.writeValueAsString(payload);
    }

    private String requiredText(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private String optionalText(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        return value == null || value.asText().isBlank() ? fallback : value.asText();
    }

    private String error(String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "FAILED");
        result.put("error", message == null ? "unknown error" : message);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ignored) {
            return "{\"status\":\"FAILED\",\"error\":\"serialization failed\"}";
        }
    }
}
