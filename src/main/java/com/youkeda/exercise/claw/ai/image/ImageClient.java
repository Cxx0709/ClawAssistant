package com.youkeda.exercise.claw.ai.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.ai.image.ImageClientException;
import com.youkeda.exercise.claw.infrastructure.common.PromptLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 图片生成客户端
 *
 * 负责调用图片生成模型（如阿里云 qwen-image-2.0）生成图片
 * 请求格式兼容阿里云百炼 multimodal-generation API
 */
@Component
public class ImageClient {

    private static final Logger log = LoggerFactory.getLogger(ImageClient.class);

    private static final int TIMEOUT_SECONDS = 120;
    /** 默认生成图片尺寸（宽*高） */
    private static final String DEFAULT_IMAGE_SIZE = "1024*1024";

    private static final String SYSTEM_PROMPT_PATH = "prompts/image-system-prompt.txt";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是 Claw助手的图片生成模块，请根据用户描述生成高质量图片。";

    private final ImageProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;

    private String systemPrompt;

    public ImageClient(ImageProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    @PostConstruct
    public void init() {
        this.systemPrompt = promptLoader.load(SYSTEM_PROMPT_PATH, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * 生成图片
     *
     * @param prompt 图片描述提示词
     * @return 生成的图片 URL，失败时返回 null
     * @throws ImageClientException API 返回错误码时抛出（如 DataInspectionFailed），供上层重试
     */
    public String generateImage(String prompt) throws ImageClientException {
        try {
            String requestBody = buildRequestBody(prompt);
            log.info("调用图片生成模型 | model={} | prompt={}", properties.getModel(), prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl()))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String imageUrl = parseResponse(response.body());
            log.info("图片生成成功 | imageUrl={}", imageUrl);
            return imageUrl;

        } catch (ImageClientException e) {
            // API 业务错误码（如 DataInspectionFailed），向上传播供重试
            throw e;
        } catch (Exception e) {
            log.error("图片生成失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 从图片 URL 下载字节流
     *
     * @param imageUrl 图片公开可访问地址
     * @return 图片字节数组，下载失败时返回 null
     */
    public byte[] downloadImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                log.info("图片下载成功 | size={} bytes | url={}", response.body().length, imageUrl);
                return response.body();
            }
            log.warn("图片下载失败 | status={} | url={}", response.statusCode(), imageUrl);
            return null;
        } catch (Exception e) {
            log.error("图片下载异常 | url={} | error={}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 构建百炼 multimodal-generation 请求体
     *
     * 格式（qwen-image-2.0 系列）：
     * {
     *   "model": "qwen-image-2.0",
     *   "input": {
     *     "messages": [
     *       { "role": "user", "content": [{ "text": "systemPrompt + userPrompt" }] }
     *     ]
     *   },
     *   "parameters": { "size": "1024*1024", "n": 1, "prompt_extend": true, "watermark": false }
     * }
     */
    private String buildRequestBody(String prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());

        // input.messages[0]
        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");

        // 合并系统提示词和用户提示词
        ArrayNode content = message.putArray("content");
        ObjectNode textItem = content.addObject();
        textItem.put("text", systemPrompt + "\n\n用户需求：" + prompt);

        // parameters
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("size", DEFAULT_IMAGE_SIZE);
        parameters.put("n", 1);
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析图片生成响应 JSON，提取图片 URL
     *
     * multimodal-generation 同步响应格式：
     * {
     *   "output": {
     *     "choices": [{
     *       "message": {
     *         "content": [{ "image": "https://..." }]
     *       }
     *     }]
     *   }
     * }
     *
     * 异步响应（开启 X-DashScope-Async 时）：
     * { "output": { "task_status": "PENDING", "task_id": "..." } }
     *
     * API 错误响应（阿里云百炼风格）：
     * { "code": "DataInspectionFailed", "message": "Output data may contain..." }
     */
    private String parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // 0. 检测 API 错误码
        JsonNode codeNode = root.get("code");
        if (codeNode != null && !codeNode.isNull()) {
            String errorCode = codeNode.asText();
            String errorMessage = root.path("message").asText("未知错误");
            throw new ImageClientException(errorCode, errorMessage);
        }

        // 1. 同步响应：output.choices[0].message.content[0].image
        JsonNode output = root.get("output");
        if (output != null) {
            JsonNode choices = output.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (content.isArray() && content.size() > 0) {
                    JsonNode image = content.get(0).get("image");
                    if (image != null) {
                        return image.asText();
                    }
                }
            }
        }

        // 2. 兼容旧格式：output.results[0].url
        if (output != null) {
            JsonNode results = output.get("results");
            if (results != null && results.isArray() && results.size() > 0) {
                JsonNode url = results.get(0).get("url");
                if (url != null) {
                    return url.asText();
                }
            }
        }

        // 3. 异步响应：返回 task_id 供后续查询
        JsonNode taskIdNode = root.path("output").path("task_id");
        if (!taskIdNode.isMissingNode()) {
            String taskId = taskIdNode.asText();
            log.info("图片生成任务已提交 | taskId={}", taskId);
            return "[异步任务] taskId=" + taskId;
        }

        log.warn("图片生成响应格式异常: {}", responseBody);
        return null;
    }
}
