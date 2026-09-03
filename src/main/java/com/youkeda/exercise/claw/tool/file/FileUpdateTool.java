package com.youkeda.exercise.claw.tool.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.file.FileService;
import com.youkeda.exercise.claw.domain.file.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件更新工具
 *
 * <p>LLM Function：{@code file_update}
 *
 * <p>当用户说「重命名」「改个名字」「把 XX 改成 YY」时，
 * Agent 自主调用此工具修改文件名。
 *
 * <p>纯文本入、JSON 字符串出，不走 pending-consumer 模式。
 */
@Component
public class FileUpdateTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileUpdateTool.class);

    private final FileService fileService;

    public FileUpdateTool(FileService fileService,
                              ToolRegistry functionRegistry,
                              ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.fileService = fileService;
    }

    @Override
    public String getName() {
        return "file_update";
    }

    @Override
    public String getDescription() {
        return "更新已保存文件的名称。当用户说「重命名」「改个名字」「把 XX 改成 YY」时调用。"
                + "需要文件 ID 和新文件名。仅修改显示名，不影响存储文件。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("file_id", "要修改的文件 ID。如果不知道 ID，可先用 file_search 搜索获取。", true)
                .string("filename", "新的文件名，需包含扩展名，如「Java面试题整理 v2.md」", true)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String userId = context.userId();

            if (userId == null || userId.isBlank()) {
                return errorJson("缺少用户 ID");
            }

            long fileId = args.path("file_id").asLong(0);
            String newFilename = args.path("filename").asText("");

            if (fileId <= 0) {
                return errorJson("请提供要修改的文件 ID（file_id 参数）");
            }
            if (newFilename.isBlank()) {
                return errorJson("请提供新的文件名（filename 参数）");
            }

            log.info("file_update 执行 | userId={} | fileId={} | newFilename={}",
                    userId, fileId, newFilename);

            FileMetadata updated = fileService.updateFileName(userId, fileId, newFilename);
            if (updated == null) {
                return errorJson("文件不存在或已被删除");
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success");
            result.put("file_id", updated.getId());
            result.put("old_filename", args.path("filename").asText(""));
            result.put("new_filename", updated.getFilename());
            result.put("message", "文件名已更新为「" + updated.getFilename() + "」");

            log.info("file_update 完成 | userId={} | fileId={} | newFilename={}",
                    userId, fileId, newFilename);
            return objectMapper.writeValueAsString(result);

        } catch (IllegalArgumentException e) {
            log.warn("file_update 参数错误 | args={}", argumentsJson, e);
            return errorJson(e.getMessage());
        } catch (Exception e) {
            log.error("file_update 执行失败 | args={}", argumentsJson, e);
            return errorJson("文件更新失败：" + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("status", "error");
            node.put("message", message);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + message + "\"}";
        }
    }
}