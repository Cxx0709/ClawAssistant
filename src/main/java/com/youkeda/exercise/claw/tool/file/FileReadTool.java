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
 * 文件读取工具
 *
 * <p>LLM Function：{@code file_read}
 *
 * <p>当用户说「看看我的笔记」「读一下」「打开某个文件」时，
 * Agent 自主调用此工具读取已保存文件的内容并返回。
 *
 * <p>纯文本入、JSON 字符串出，不走 pending-consumer 模式。
 * 内容超过 5000 字符时自动截断并标记 truncated。
 */
@Component
public class FileReadTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private final FileService fileService;

    public FileReadTool(FileService fileService,
                            ToolRegistry functionRegistry,
                            ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.fileService = fileService;
    }

    @Override
    public String getName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "读取用户已保存文件的内容。当用户提到「读一下」「看看」「打开」某个文件时调用。"
                + "支持 md/txt（直接读取）和 pdf/docx（Tika 提取文本，仅返回提取结果的前 5000 字符）。"
                + "支持通过 file_id 精确查找或 filename 模糊匹配。不经过用户请求不要读取文件。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("file_id", "文件 ID（优先使用）。当用户明确说某个文件时，从文件名搜索后传入此 ID。", false)
                .string("filename", "文件名搜索关键词。当用户说「我的Java笔记」「数据库笔记」等模糊名称时使用，"
                        + "系统会模糊匹配文件名，返回第一个匹配结果。", false)
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
            String filename = args.path("filename").asText("");

            if (fileId <= 0 && filename.isBlank()) {
                return errorJson("请提供 file_id 或 filename 参数");
            }

            log.info("file_read 执行 | userId={} | fileId={} | filename={}", userId, fileId, filename);

            // 优先 file_id 精确查找
            if (fileId > 0) {
                return readById(userId, fileId);
            }

            // 降级 filename 模糊搜索 → 取第一个
            var results = fileService.searchFiles(userId, filename, null, 1);
            if (results.isEmpty()) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "error");
                result.put("message", "未找到匹配的文件，请确认文件名是否正确");
                return objectMapper.writeValueAsString(result);
            }

            return readById(userId, results.get(0).getId());

        } catch (Exception e) {
            log.error("file_read 执行失败 | args={}", argumentsJson, e);
            return errorJson("文件读取失败：" + e.getMessage());
        }
    }

    private String readById(String userId, Long fileId) throws Exception {
        FileService.FileContent fileContent = fileService.readFileText(userId, fileId);
        if (fileContent == null) {
            return errorJson("文件不存在或已被删除");
        }

        FileMetadata meta = fileContent.metadata();

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "success");
        result.put("file_id", meta.getId());
        result.put("filename", meta.getFilename());
        result.put("file_type", meta.getFileType());
        result.put("size", meta.getSize());
        result.put("content", fileContent.content());
        result.put("truncated", fileContent.truncated());
        result.put("created_time", meta.getCreatedTime() != null ? meta.getCreatedTime() : "");

        if (fileContent.message() != null) {
            result.put("message", fileContent.message());
        }

        log.info("file_read 完成 | userId={} | fileId={} | truncated={}",
                userId, fileId, fileContent.truncated());
        return objectMapper.writeValueAsString(result);
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
