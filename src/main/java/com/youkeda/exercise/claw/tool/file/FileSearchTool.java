package com.youkeda.exercise.claw.tool.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.file.FileService;
import com.youkeda.exercise.claw.domain.file.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件搜索工具
 *
 * <p>LLM Function：{@code file_search}
 *
 * <p>当用户说「找一下我的笔记」「有哪些文件」「搜索」时，
 * Agent 自主调用此工具搜索用户已保存的文件。
 *
 * <p>返回文件列表含文件名、类型、大小、创建时间和摘要信息。
 */
@Component
public class FileSearchTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileSearchTool.class);

    private final FileService fileService;

    public FileSearchTool(FileService fileService,
                              ToolRegistry functionRegistry,
                              ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.fileService = fileService;
    }

    @Override
    public String getName() {
        return "file_search";
    }

    @Override
    public String getDescription() {
        return "搜索用户已保存的文件。当用户说「找一下」「搜索」「有哪些」文件时调用。"
                + "返回文件列表（含文件名、类型、大小、摘要和创建时间）。"
                + "可按关键词和文件类型过滤。不经过用户请求不要主动搜索文件。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("keyword", "搜索关键词，支持文件名模糊匹配，如「数据库」「Java」「笔记」", true)
                .string("file_type", "按文件类型过滤（可选），如 md / txt / pdf / docx", false)
                .integer("limit", "返回数量限制（默认 10，最大 50）", false)
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

            String keyword = args.path("keyword").asText("");
            String fileType = args.path("file_type").asText("");
            int limit = args.path("limit").asInt(10);

            if (keyword.isBlank()) {
                return errorJson("请提供搜索关键词（keyword 参数）");
            }

            log.info("file_search 执行 | userId={} | keyword={} | fileType={} | limit={}",
                    userId, keyword, fileType, limit);

            List<FileMetadata> files = fileService.searchFiles(userId, keyword, fileType, limit);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success");
            result.put("total", files.size());
            result.put("keyword", keyword);

            ArrayNode fileArray = result.putArray("files");
            for (FileMetadata meta : files) {
                ObjectNode item = fileArray.addObject();
                item.put("file_id", meta.getId());
                item.put("filename", meta.getFilename());
                item.put("file_type", meta.getFileType());
                item.put("size", meta.getSize());
                item.put("summary", meta.getSummary() != null ? meta.getSummary() : "");
                item.put("created_time", meta.getCreatedTime() != null ? meta.getCreatedTime() : "");
            }

            if (files.isEmpty()) {
                result.put("message", "未找到匹配的文件");
            } else {
                result.put("message", "找到 " + files.size() + " 个匹配的文件");
            }

            log.info("file_search 完成 | userId={} | keyword={} | total={}", userId, keyword, files.size());
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("file_search 执行失败 | args={}", argumentsJson, e);
            return errorJson("文件搜索失败：" + e.getMessage());
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
