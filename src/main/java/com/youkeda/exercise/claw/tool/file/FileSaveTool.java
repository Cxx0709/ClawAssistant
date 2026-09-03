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
 * 文件保存工具
 *
 * <p>LLM Function：{@code file_save}
 *
 * <p>当用户说「保存这段笔记」「帮我存一下」「记下来」时，
 * Agent 自主调用此工具将文本内容保存为用户文件。
 *
 * <p>纯文本入、JSON 字符串出，不走 pending-consumer 模式。
 */
@Component
public class FileSaveTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileSaveTool.class);

    private final FileService fileService;

    public FileSaveTool(FileService fileService,
                            ToolRegistry functionRegistry,
                            ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.fileService = fileService;
    }

    @Override
    public String getName() {
        return "file_save";
    }

    @Override
    public String getDescription() {
        return "保存文本内容到用户的文件存储。当用户说「保存这段内容」「帮我存一下」「记下来」「记录一下」时调用。"
                + "调用后文件会自动保存到用户的知识库。文件名必须包含扩展名（md/txt/pdf/docx）。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("filename", "文件名，需包含扩展名，如「操作系统学习笔记.md」。"
                        + "支持 md, txt, pdf, docx 格式。", true)
                .string("content", "要保存的文件内容（纯文本 / markdown 格式）", true)
                .string("category", "分类标签（可选），如「学习笔记」「代码片段」「面试题」", false)
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

            String filename = args.path("filename").asText("");
            String content = args.path("content").asText("");
            String category = args.path("category").asText("");

            if (filename.isBlank()) {
                return errorJson("请提供文件名（filename 参数），如「笔记.md」");
            }
            if (content.isBlank()) {
                return errorJson("请提供要保存的文件内容（content 参数）");
            }

            log.info("file_save 执行 | userId={} | filename={} | size={}",
                    userId, filename, content.length());

            FileMetadata metadata = fileService.saveTextFile(userId, content, filename);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success");
            result.put("file_id", metadata.getId());
            result.put("filename", metadata.getFilename());
            result.put("file_type", metadata.getFileType());
            result.put("size", metadata.getSize());
            result.put("created_time", metadata.getCreatedTime() != null ? metadata.getCreatedTime() : "");
            result.put("message", "文件已保存到你的知识库");

            log.info("file_save 完成 | userId={} | fileId={} | filename={}",
                    userId, metadata.getId(), filename);
            return objectMapper.writeValueAsString(result);

        } catch (IllegalArgumentException e) {
            log.warn("file_save 参数错误 | args={}", argumentsJson, e);
            return errorJson(e.getMessage());
        } catch (Exception e) {
            log.error("file_save 执行失败 | args={}", argumentsJson, e);
            return errorJson("文件保存失败：" + e.getMessage());
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
