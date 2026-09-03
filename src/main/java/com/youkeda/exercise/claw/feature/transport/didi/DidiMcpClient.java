package com.youkeda.exercise.claw.feature.transport.didi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 滴滴 MCP JSON-RPC 2.0 客户端
 *
 * <p>基于 MCP（Model Context Protocol）标准协议实现，与滴滴出行 MCP Server 通信。
 * 通过 JSON-RPC 2.0 协议完成工具发现和调用。
 *
 * <p>核心能力：
 * <ol>
 *   <li><b>工具发现</b> — 启动时调用 {@code tools/list} 获取可用工具列表并缓存</li>
 *   <li><b>通用调用</b> — {@link #callTool} 封装 {@code tools/call} 方法</li>
 * </ol>
 *
 * <p>设计原则：
 * <ul>
 *   <li>不硬编码任何 MCP 工具名，通过 {@code tools/list} 动态发现</li>
 *   <li>不包含业务逻辑，只做协议通信和 JSON 序列化/反序列化</li>
 *   <li>业务编排由上层 {@code DidiRideService} 负责</li>
 * </ul>
 */
@Component
public class DidiMcpClient {

    private static final Logger log = LoggerFactory.getLogger(DidiMcpClient.class);

    /** JSON-RPC 请求 ID 原子递增 */
    private final AtomicInteger requestId = new AtomicInteger(1);

    /** 已发现的 MCP 工具缓存（name → ToolInfo） */
    private final ConcurrentMap<String, ToolInfo> availableTools = new ConcurrentHashMap<>();

    /** MCP 工具列表是否已成功发现 */
    private volatile boolean toolsDiscovered = false;

    private final DidiMcpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DidiMcpClient(DidiMcpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeout()))
                .build();
    }

    /**
     * 初始化：检查配置后自动发现 MCP 工具列表。
     * 工具发现失败不会阻止应用启动，只会打印警告日志。
     */
    @PostConstruct
    public void init() {
        if (!isConfigValid()) {
            log.warn("滴滴 MCP 配置无效，跳过工具发现。请配置 didi.mcp.api-key");
            return;
        }
        discoverTools();
    }

    // ==================== 工具发现 ====================

    /**
     * 调用 MCP {@code tools/list} 方法发现可用工具列表并缓存。
     * 可在运行时手动调用以刷新工具列表。
     */
    public void discoverTools() {
        try {
            JsonNode result = executeMcpRequest("tools/list", emptyParams());
            JsonNode tools = result.get("tools");
            if (tools != null && tools.isArray()) {
                availableTools.clear();
                for (JsonNode tool : tools) {
                    String name = tool.path("name").asText();
                    String description = tool.path("description").asText("");
                    JsonNode inputSchema = tool.get("inputSchema");
                    availableTools.put(name, new ToolInfo(name, description, inputSchema));
                    log.debug("发现滴滴 MCP 工具: name={}, desc={}", name, description);
                }
                toolsDiscovered = true;
                log.info("滴滴 MCP 工具发现完成 | 共 {} 个工具: {}",
                        availableTools.size(), availableTools.keySet());
            }
        } catch (Exception e) {
            toolsDiscovered = false;
            log.warn("滴滴 MCP 工具发现失败，打车功能可能不可用 | error={}", e.getMessage());
        }
    }

    // ==================== 通用 MCP 调用 ====================

    /**
     * 通用 MCP 工具调用。
     * 通过 JSON-RPC 2.0 的 {@code tools/call} 方法调用指定工具。
     *
     * @param name      工具名（如 "taxi_estimate"）
     * @param arguments 工具参数字典（String → String/Number/Boolean）
     * @return MCP 响应的原始 result 节点（含 content 数组）
     * @throws DidiMcpException 调用失败时抛出
     */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        if (!isConfigValid()) {
            throw new DidiMcpException("滴滴 MCP 未配置，无法调用工具: " + name);
        }

        if (!availableTools.containsKey(name)) {
            log.warn("MCP 工具 {} 不在已发现列表中，尝试调用", name);
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        ObjectNode argsNode = params.putObject("arguments");
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (value == null) {
                    argsNode.putNull(key);
                } else if (value instanceof String s) {
                    argsNode.put(key, s);
                } else if (value instanceof Number n) {
                    argsNode.put(key, n.doubleValue());
                } else if (value instanceof Boolean b) {
                    argsNode.put(key, b);
                } else {
                    argsNode.put(key, String.valueOf(value));
                }
            });
        }

        log.info("MCP callTool | name={} | args={}", name, argsNode);
        return executeMcpRequest("tools/call", params);
    }

    /**
     * 调用 MCP 工具并自动提取文本类型响应内容。
     * <p>大部分滴滴 MCP 工具的 response.content[0].type = "text"，
     * text 字段内容为 JSON 字符串。此方法自动提取并解析该 JSON。
     *
     * @param name      工具名
     * @param arguments 工具参数
     * @return 从 content[0].text 中解析出的 JSON 节点
     * @throws DidiMcpException 无文本内容或解析失败时抛出
     */
    public JsonNode callToolWithTextResult(String name, Map<String, Object> arguments) {
        JsonNode result = callTool(name, arguments);

        // 检查是否有 structuredContent（taxi_estimate 等工具使用此字段返回结构化数据）
        JsonNode structuredContent = result.get("structuredContent");
        boolean hasStructuredContent = structuredContent != null && !structuredContent.isNull();

        JsonNode content = result.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            if (hasStructuredContent) {
                log.info("MCP 工具 {} 无 content 数组，返回 structuredContent", name);
                return structuredContent;
            }
            throw new DidiMcpException("MCP 工具 " + name + " 响应缺少 content 数组");
        }

        String text = content.get(0).path("text").asText("");
        if (text.isBlank()) {
            if (hasStructuredContent) {
                log.info("MCP 工具 {} content[0].text 为空，返回 structuredContent", name);
                return structuredContent;
            }
            throw new DidiMcpException("MCP 工具 " + name + " 响应 content[0].text 为空");
        }

        try {
            JsonNode parsed = objectMapper.readTree(text);
            // 如果 result 中有 structuredContent 且 parsed 是对象节点，附加到返回结果中
            if (hasStructuredContent && parsed instanceof ObjectNode) {
                ((ObjectNode) parsed).set("structuredContent", structuredContent);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("MCP 工具 {} 返回的非 JSON 文本内容: {}", name, text);
            // 有 structuredContent 则优先返回结构化数据
            if (hasStructuredContent) {
                return structuredContent;
            }
            // 非 JSON 文本包装为 TextNode
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("text", text);
            return wrapper;
        }
    }

    /**
     * 获取已发现的所有 MCP 工具信息（只读视图）
     */
    public ConcurrentMap<String, ToolInfo> getAvailableTools() {
        return availableTools;
    }

    /**
     * 判断工具列表是否已成功发现
     */
    public boolean isToolsDiscovered() {
        return toolsDiscovered;
    }

    // ==================== 内部 JSON-RPC 2.0 执行 ====================

    /**
     * 执行 MCP JSON-RPC 2.0 请求。
     *
     * @param method RPC 方法名（如 "tools/list"、"tools/call"）
     * @param params RPC 参数
     * @return result 节点（JSON-RPC 响应的 result 字段）
     */
    private JsonNode executeMcpRequest(String method, JsonNode params) {
        try {
            // 1. 构建 JSON-RPC 2.0 请求体
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("jsonrpc", "2.0");
            requestBody.put("id", requestId.getAndIncrement());
            requestBody.put("method", method);
            requestBody.set("params", params);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("MCP 请求 | method={} | body={}", method, truncate(requestJson, 500));

            // 2. 构建 URL（API Key 通过查询参数传递，URL 编码防特殊字符破坏结构）
            String url = properties.getBaseUrl() + "?key="
                    + URLEncoder.encode(properties.getApiKey(), StandardCharsets.UTF_8);

            // 3. 发送 HTTP POST
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeout()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 4. 检查 HTTP 状态
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String bodyPreview = response.body();
                if (bodyPreview.length() > 200) bodyPreview = bodyPreview.substring(0, 200) + "...";
                throw new DidiMcpException("MCP HTTP " + response.statusCode()
                        + " | method=" + method + " | body=" + bodyPreview);
            }

            // 5. 解析 JSON-RPC 2.0 响应
            JsonNode root = objectMapper.readTree(response.body());
            log.debug("MCP 响应 | method={} | status={} | body={}",
                    method, response.statusCode(), truncate(response.body(), 500));

            // 检查 JSON-RPC 错误
            if (root.has("error") && !root.get("error").isNull()) {
                JsonNode error = root.get("error");
                int code = error.path("code").asInt(-1);
                String msg = error.path("message").asText("未知 MCP 错误");
                log.error("MCP JSON-RPC 错误 | method={} | code={} | message={}",
                        method, code, msg);
                throw new DidiMcpException("MCP JSON-RPC 错误 [code=" + code + "] " + msg
                        + " | method=" + method);
            }

            // 6. 提取 result
            JsonNode result = root.get("result");
            if (result == null) {
                throw new DidiMcpException("MCP 响应缺少 result 字段 | method=" + method
                        + " | response=" + root);
            }

            // 打印 result 的关键结构信息
            boolean hasContent = result.has("content") && result.get("content").isArray() && !result.get("content").isEmpty();
            boolean hasStructuredContent = result.has("structuredContent") && !result.get("structuredContent").isNull();
            if (hasContent) {
                String textPreview = result.get("content").get(0).path("text").asText("");
                if (textPreview.length() > 200) textPreview = textPreview.substring(0, 200) + "...";
                log.info("MCP result | method={} | content[0].type={} | text_preview={} | hasStructuredContent={}",
                        method, result.get("content").get(0).path("type").asText(""), textPreview, hasStructuredContent);
            } else {
                log.info("MCP result | method={} | keys={} | hasContent={} | hasStructuredContent={}",
                        method, joinFieldNames(result), hasContent, hasStructuredContent);
            }
            return result;

        } catch (DidiMcpException e) {
            throw e;
        } catch (Exception e) {
            throw new DidiMcpException("MCP 调用失败 | method=" + method + " | error=" + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    private boolean isConfigValid() {
        return properties.getApiKey() != null
                && !properties.getApiKey().isBlank()
                && !"YOUR_MCP_KEY".equals(properties.getApiKey());
    }

    /**
     * 构建空的 params 节点（用于 tools/list 等无需参数的请求）
     */
    private ObjectNode emptyParams() {
        ObjectNode params = objectMapper.createObjectNode();
        // MCP 规范允许传入 _meta 元信息
        ObjectNode meta = params.putObject("_meta");
        meta.put("progressToken", 1);
        return params;
    }

    /**
     * 拼接 JsonNode 的字段名为逗号分隔字符串
     */
    private static String joinFieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.toString();
    }

    /**
     * 截断字符串用于日志（防敏感信息全量落盘）
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ==================== 内部类型 ====================

    /**
     * MCP 工具信息记录
     *
     * @param name        工具名
     * @param description 工具描述
     * @param inputSchema 输入参数 JSON Schema
     */
    public record ToolInfo(String name, String description, JsonNode inputSchema) {
    }
}
