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
 * 文件列表工具
 *
 * <p>LLM Function：{@code file_list}
 *
 * <p>当用户说「我的文件有哪些」「列出所有文件」「显示文件列表」时，
 * Agent 自主调用此工具查询用户已保存的文件，支持分页。
 *
 * <p>纯文本入、JSON 字符串出，不走 pending-consumer 模式。
 */
@Component
public class FileListTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileListTool.class);

    private final FileService fileService;

    public FileListTool(FileService fileService,
                            ToolRegistry functionRegistry,
                            ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.fileService = fileService;
    }

    @Override
    public String getName() {
        return "file_list";
    }

    @Override
    public String getDescription() {
        return "列出用户已保存的文件列表，支持分页。当用户说「我的文件」「列出文件」「显示所有文件」时调用。"
                + "返回文件列表（含文件名、类型、大小、创建时间）。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("page", "页码（从 1 开始，默认 1）", false)
                .integer("size", "每页数量（默认 20，最大 100）", false)
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

            int page = args.path("page").asInt(1);
            int size = args.path("size").asInt(20);

            log.info("file_list 执行 | userId={} | page={} | size={}", userId, page, size);

            FileService.FileListResult result = fileService.listFilesPaginated(userId, page, size);

            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", "success");
            root.put("total", result.total());
            root.put("page", result.page());
            root.put("page_size", result.pageSize());
            root.put("total_pages", result.totalPages());
            root.put("has_more", result.page() < result.totalPages());

            ArrayNode fileArray = root.putArray("files");
            for (FileMetadata meta : result.files()) {
                ObjectNode item = fileArray.addObject();
                item.put("file_id", meta.getId());
                item.put("filename", meta.getFilename());
                item.put("file_type", meta.getFileType());
                item.put("size", meta.getSize());
                item.put("size_display", formatSize(meta.getSize()));
                item.put("source", meta.getSource() != null ? meta.getSource() : "");
                item.put("created_time", meta.getCreatedTime() != null ? meta.getCreatedTime() : "");
            }

            if (result.files().isEmpty()) {
                root.put("message", "还没有保存任何文件");
            } else {
                root.put("message", "共 " + result.total() + " 个文件，当前第 "
                        + result.page() + "/" + result.totalPages() + " 页");
            }

            log.info("file_list 完成 | userId={} | total={} | page={}/{}",
                    userId, result.total(), result.page(), result.totalPages());
            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("file_list 执行失败 | args={}", argumentsJson, e);
            return errorJson("文件列表查询失败：" + e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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