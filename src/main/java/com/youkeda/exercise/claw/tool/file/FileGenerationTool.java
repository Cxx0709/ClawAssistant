package com.youkeda.exercise.claw.tool.file;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.file.FileGenerationService;
import com.youkeda.exercise.claw.feature.file.FileGenerationService.FileGenerationResult;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件生成工具
 *
 * 封装 FileGenerationService，根据用户请求生成 PDF、Word 或 Markdown 文件。
 * 作为 Tool 暴露，启动时自动注册到 ToolRegistry。
 */
@Component
public class FileGenerationTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FileGenerationTool.class);
    private final FileGenerationService fileGenerationService;

    public FileGenerationTool(FileGenerationService fileGenerationService,
                              ToolRegistry llmFunctionRegistry,
                              ObjectMapper objectMapper) {
        super(llmFunctionRegistry, objectMapper);
        this.fileGenerationService = fileGenerationService;
    }

    /**
     * 消费待发送的文件数据
     * <p>被 {@code ChatHandler} 在工具调用循环结束后调用，如果存在则发送文件而非纯文本。</p>
     *
     * @return 待发送的文件数据，没有则返回 null
     */
    // ==================== Tool ====================

    @Override
    public String getName() {
        return "file_generate";
    }

    @Override
    public String getDescription() {
        return "根据文字描述生成 PDF、Word 或 Markdown 文件，支持总结对话、生成报告和导出文档。用户要求生成文件时调用此工具";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "string");
        format.put("description", "文件格式，pdf、docx 或 md");
        format.put("enum", objectMapper.createArrayNode().add("pdf").add("docx").add("md"));

        return schema()
                .string("topic", "文件主题或内容描述，如：今天的对话总结、项目报告等", true)
                .raw("format", format, true)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode topicNode = args.get("topic");
            if (topicNode == null) {
                return "{\"error\": \"缺少必填参数: topic\"}";
            }

            String topic = topicNode.asText();
            log.info("FileGenerationTool LLM调用 | topic={}", topic);

            // 读取 LLM 指定的格式
            String format = args.has("format") ? args.get("format").asText() : null;

            // 用 topic 作为用户消息调用 generate（内部会做 LLM 内容生成 + 渲染）
            FileGenerationResult result = fileGenerationService.generate(topic, format);
            if (result == null) {
                return "{\"error\": \"文件生成失败\"}";
            }

            log.info("文件生成成功 | fileName={} | size={}bytes",
                    result.fileName(), result.fileBytes().length);

            context.artifacts().emit(ArtifactKind.FILE, result.fileBytes(),
                    mimeType(result.fileName()), result.fileName(), result.description());

            return "{\"fileName\": \"" + result.fileName()
                    + "\", \"description\": \"" + result.description()
                    + "\", \"size\": " + result.fileBytes().length + "}";

        } catch (Exception e) {
            log.error("FileGenerationTool LLM执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String mimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".md")) return "text/markdown; charset=utf-8";
        return "application/octet-stream";
    }
}
