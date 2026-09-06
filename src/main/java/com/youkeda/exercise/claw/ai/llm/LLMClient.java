package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.infrastructure.common.PromptLoader;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * LLM 客户端
 *
 * 封装大模型 HTTP 调用，兼容 OpenAI 协议格式
 */
@Component
public class LLMClient {

    private static final int TIMEOUT_SECONDS = 60;
    private static final String SYSTEM_PROMPT_PATH = "prompts/system-prompt.txt";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是 Claw助手，一个智能AI助手。";

    /** LLM 调用最大重试次数（指数退避） */
    private static final int MAX_RETRIES = 3;
    /** 重试初始退避延迟（毫秒） */
    private static final long RETRY_BASE_DELAY_MS = 300;

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final LLMProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    /** 协议序列化（含孤立 tool 丢弃兜底），构造时内部创建（保持构造签名不变）。 */
    private final LLMAdapter llmAdapter;

    /** 保留当前请求线程最近一次失败类型，便于活动日志直接定位。 */
    private final ThreadLocal<String> lastFailureSummary = new ThreadLocal<>();
    /** 401/402/403/400 等配置性错误不应再做流式/全量/无工具重试。 */
    private final ThreadLocal<Boolean> permanentFailure = new ThreadLocal<>();

    private String systemPrompt;

    public LLMClient(LLMProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        this.llmAdapter = new LLMAdapter(objectMapper);
    }

    @PostConstruct
    public void init() {
        this.systemPrompt = promptLoader.load(SYSTEM_PROMPT_PATH, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * 调用大模型生成回复（无历史消息，单轮对话）
     *
     * @param text   用户消息
     * @return 模型回复内容，调用失败时返回 null
     */
    public String chat(String text) {
        return chat(text, List.of());
    }

    /**
     * 调用大模型生成回复（带历史消息，多轮对话）
     *
     * @param text    用户消息
     * @param history 历史消息列表（按时间正序）
     * @return 模型回复内容，调用失败时返回 null
     */
    public String chat(String text, List<Message> history) {
        return callLLM(systemPrompt, text, history);
    }

    /**
     * 使用自定义系统提示词调用大模型（带历史消息）
     *
     * @param systemPrompt 自定义系统提示词
     * @param text         用户消息
     * @param history      历史消息列表（按时间正序），为空时等价于无历史调用
     * @return 模型回复内容，调用失败时返回 null
     */
    public String chatWithSystemPrompt(String systemPrompt, String text, List<Message> history) {
        return callLLM(systemPrompt, text, history != null ? history : List.of());
    }

    /**
     * 使用自定义系统提示词调用大模型（无历史消息）
     *
     * @param systemPrompt 自定义系统提示词
     * @param text         用户消息
     * @return 模型回复内容，调用失败时返回 null
     */
    public String chatWithSystemPrompt(String systemPrompt, String text) {
        return callLLM(systemPrompt, text, List.of());
    }

    /**
     * 使用自定义系统提示词调用大模型，并限制本次输出 token。
     */
    public String chatWithSystemPrompt(String systemPrompt, String text, int maxTokens) {
        return callLLM(systemPrompt, text, List.of(), maxTokens);
    }

    /**
     * 调用大模型（内部方法）
     */
    private String callLLM(String systemPrompt, String text, List<Message> history) {
        return callLLM(systemPrompt, text, history, 0);
    }

    private String callLLM(String systemPrompt, String text, List<Message> history,
                           int maxTokens) {
        try {
            // 1. 构建请求体
            String requestBody = buildRequestBody(systemPrompt, text, history, maxTokens);
            log.info("调用LLM，message={}，historySize={}", text, history.size());

            // 2. 发送 + 解析（含重试）
            String result = retryExecute(() -> {
                try {
                    return doCallLLM(requestBody);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, MAX_RETRIES);
            if (result == null || result.isBlank()) {
                lastFailureSummary.set("LLM 返回空正文");
            } else {
                clearFailure();
            }
            return result;

        } catch (Exception e) {
            recordFailure(e);
            log.error("LLM调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 单次 LLM HTTP 调用（不含重试）。非 2xx 抛 {@link LLMHttpException} 触发外层重试。
     */
    private String doCallLLM(String requestBody) throws Exception {
        String url = properties.getBaseUrl() + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkHttpStatus(response.statusCode());

        String reply = parseResponse(response.body());
        if (reply == null) {
            log.warn("LLM响应不可用 | status={}", response.statusCode());
        } else if (reply.isBlank()) {
            log.warn("LLM响应成功但正文为空 | status={}", response.statusCode());
        } else {
            log.info("LLM响应成功 | contentLength={}", reply.length());
        }
        return reply;
    }

    /**
     * 构建请求 JSON 体
     */
    private String buildRequestBody(String systemPrompt, String text, List<Message> history) throws Exception {
        return buildRequestBody(systemPrompt, text, history, 0);
    }

    String buildRequestBody(String systemPrompt, String text, List<Message> history,
                            int maxTokens) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }

        ArrayNode messages = root.putArray("messages");

        // system prompt
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        // history messages
        for (Message msg : history) {
            ObjectNode historyMsg = messages.addObject();
            historyMsg.put("role", msg.role().value());
            historyMsg.put("content", msg.content());
        }

        // current user message
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", text);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 LLM 响应 JSON，提取回复文本
     */
    private String parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                String reply = content != null && !content.isNull()
                        ? content.asText() : null;
                if (reply == null || reply.isBlank()) {
                    String finishReason = choice.path("finish_reason").asText("unknown");
                    int reasoningLength = message.path("reasoning_content")
                            .asText("").length();
                    log.warn("LLM 返回空正文 | finishReason={} | reasoningLength={}",
                            finishReason, reasoningLength);
                }
                return reply;
            }
        }

        log.warn("LLM 响应格式异常: {}", responseBody);
        return null;
    }

    // ==================== Tool Calling 支持 ====================

    /**
     * 获取启动时加载的 system prompt（供 ReActAgentExecutor 构建动态 prompt 使用）
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 带工具定义和自定义 system prompt 的 LLM 调用
     * <p>此方法接受显式的 per-request system prompt，不会使用实例字段 {@link #systemPrompt}。
     * 适用于 Skill 场景（不同 Skill 有不同领域上下文）。
     *
     * @param systemPrompt 本次请求的完整 system prompt
     * @param messages     完整消息列表（不含 system prompt）
     * @param tools        工具定义列表
     * @return 结构化响应（可能包含 {@link LLMResponse.ToolCall}），失败返回 null
     */
    public LLMResponse chatWithTools(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        try {
            String requestBody = buildRequestBodyWithTools(systemPrompt, messages, tools);
            log.debug("LLM 请求（含 {} 个工具定义，自定义 system prompt）", tools != null ? tools.size() : 0);

            // 发送 + 解析（含重试）
            LLMResponse result = retryExecute(() -> {
                try {
                    return doChatWithTools(requestBody);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, MAX_RETRIES);
            if (result == null) {
                lastFailureSummary.set("LLM 响应解析失败");
            } else {
                clearFailure();
            }
            return result;

        } catch (Exception e) {
            recordFailure(e);
            log.error("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 单次带工具定义的 LLM 调用（不含重试）。非 2xx 抛 {@link LLMHttpException} 触发外层重试。
     */
    private LLMResponse doChatWithTools(String requestBody) throws Exception {
        String url = properties.getBaseUrl() + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        checkHttpStatus(response.statusCode());

        String responseBody = response.body();
        log.debug("LLM 原始响应 | status={} | body={}",
                response.statusCode(), truncate(responseBody, 1000));
        LLMResponse result = parseStructuredResponse(responseBody);
        if (result == null) {
            log.warn("LLM 响应解析失败 | status={} | body={}",
                    response.statusCode(), truncate(responseBody, 500));
        }
        return result;
    }

    /**
     * 带工具定义的 LLM 调用（使用默认 system prompt）
     *
     * @param messages 完整消息列表（已包含 system prompt 之外的所有 user/assistant/tool 消息）
     * @param tools    工具定义列表（为空时等价于普通 chat）
     * @return 结构化响应（可能包含 {@link LLMResponse.ToolCall}），失败返回 null
     */
    public LLMResponse chatWithTools(List<Message> messages, List<ToolDefinition> tools) {
        return chatWithTools(systemPrompt, messages, tools);
    }

    // ==================== 流式支持（Web 打字机推送） ====================

    /**
     * 带工具定义与文本增量回调的 LLM 调用（SSE 流式，Web 端用）。
     *
     * <p>请求体置 {@code "stream": true}，逐行解析响应流：
     * <ul>
     *   <li>正文内容增量 → 实时回调 {@code contentSink}（逐字推送）</li>
     *   <li>tool_calls → 按 index 增量合并（跨 chunk 拼接 id/name/arguments），返回结构化结果</li>
     * </ul>
     *
     * <p>流式读取任何异常都会<b>降级</b>为全量调用 {@link #chatWithTools(String, List, List)}，
     * 并把完整正文经 {@code contentSink} 补发一次，保证不丢文本、调用方语义不变。
     * Web 流式链路使用本方法；非流式调用仍走全量 {@link #chatWithTools}。
     *
     * @param systemPrompt 本次请求的完整 system prompt
     * @param messages     完整消息列表（不含 system prompt）
     * @param tools        工具定义列表
     * @param contentSink  用户可见正文增量回调，可为 null（null 时仅返回结构化结果）
     * @return 结构化响应，失败返回 null
     */
    public LLMResponse chatWithToolsStreaming(String systemPrompt, List<Message> messages,
                                              List<ToolDefinition> tools,
                                              Consumer<String> contentSink) {
        Consumer<String> sink = contentSink != null ? contentSink : ignored -> { };
        if (properties.isStreamDisabled()) {
            log.info("流式调用已禁用，直接走全量调用");
            return chatWithTools(systemPrompt, messages, tools);
        }
        try {
            String requestBody = buildRequestBodyWithTools(systemPrompt, messages, tools, true);
            log.info("LLM 流式调用 | messages={} | tools={}",
                    messages.size(), tools != null ? tools.size() : 0);
            LLMResponse streamed = doChatWithToolsStreaming(requestBody, sink);
            if (streamed != null
                    && (streamed.getContent() == null || streamed.getContent().isBlank())
                    && !streamed.isToolCall()) {
                log.warn("LLM 流式返回无正文内容，降级为全量调用 | reasoningLength={}",
                        streamed.getReasoningContent() != null
                                ? streamed.getReasoningContent().length() : 0);
                return chatWithTools(systemPrompt, messages, tools);
            }
            return streamed;
        } catch (Exception e) {
            recordFailure(e);
            if (hasPermanentFailure()) {
                log.error("LLM 流式调用遇到不可重试错误，停止降级 | error={}",
                        getLastFailureSummary());
                return null;
            }
            log.warn("LLM 流式调用失败，降级为全量调用 | error={}", e.getMessage());
            LLMResponse fallback = chatWithTools(systemPrompt, messages, tools);
            if (fallback != null && fallback.getContent() != null
                    && !fallback.getContent().isBlank()) {
                try {
                    sink.accept(fallback.getContent());
                } catch (RuntimeException ignore) {
                    // 回调侧已关闭，仅降级返回全文，不向外抛
                }
            }
            return fallback;
        }
    }

    /**
     * 单次流式 LLM 调用（不含重试）。非 2xx 抛 {@link LLMHttpException}。
     * 读取中断等 IO 异常由外层降级全量处理。
     */
    private LLMResponse doChatWithToolsStreaming(String requestBody, Consumer<String> sink)
            throws Exception {
        String url = properties.getBaseUrl() + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        checkHttpStatus(response.statusCode());

        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        String finishReason = null;
        // index → 分片累积器（tool_calls 增量跨 chunk 拼接）
        Map<Integer, StreamToolCallAccumulator> toolCallAccumulators = new LinkedHashMap<>();

        Duration idleTimeout = Duration.ofSeconds(
                Math.max(1, properties.getStreamIdleTimeoutSeconds()));
        // 流式总时长上限：推理模型持续输出 token 会使空闲超时永不触发，
        // 必须加总时长上限防止调用无限挂起。
        long streamStartMs = System.currentTimeMillis();
        Duration totalTimeout = Duration.ofSeconds(TIMEOUT_SECONDS * 3L);
        try (StreamLineReader reader = new StreamLineReader(response.body())) {
            while (true) {
                if (System.currentTimeMillis() - streamStartMs > totalTimeout.toMillis()) {
                    throw new java.net.http.HttpTimeoutException(
                            "LLM 流式响应总时长超过 " + totalTimeout.toSeconds() + " 秒，已中止");
                }
                String line = reader.readLine(idleTimeout);
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root;
                try {
                    root = objectMapper.readTree(data);
                } catch (Exception e) {
                    log.warn("流式响应 JSON 解析失败 | line={}", truncate(data, 200));
                    continue;
                }
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                JsonNode finish = choice.path("finish_reason");
                if (finish != null && !finish.isNull() && !finish.asText().isEmpty()) {
                    finishReason = finish.asText();
                }
                JsonNode delta = choice.path("delta");
                if (delta == null || delta.isMissingNode()) {
                    continue;
                }
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String chunk = delta.get("content").asText();
                    if (!chunk.isEmpty()) {
                        content.append(chunk);
                        try {
                            sink.accept(chunk);
                        } catch (RuntimeException e) {
                            log.warn("流式内容回调失败（客户端可能已断开），停止推送 | error={}",
                                    e.getMessage());
                            // 仍继续读取，保证返回完整结果
                        }
                    }
                }
                // 推理模型会把思考过程放在独立字段中。它不应推送给用户，
                // 但需要保留给后续工具轮次，并用于诊断“只有思考、没有正文”的响应。
                if (delta.has("reasoning_content")
                        && !delta.get("reasoning_content").isNull()) {
                    reasoningContent.append(delta.get("reasoning_content").asText());
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    mergeStreamToolCalls(toolCalls, toolCallAccumulators);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("流式读取中断: " + e.getMessage(), e);
        }

        return buildStreamedResponse(
                content, reasoningContent, toolCallAccumulators, finishReason);
    }

    /**
     * 在独立守护线程中执行可能无限阻塞的 {@link BufferedReader#readLine()}，调用线程通过队列
     * 设置逐行空闲超时。关闭时同时取消 HTTP 响应体，确保断流连接不会长期占用 webchat 线程。
     */
    static final class StreamLineReader implements AutoCloseable {
        private final InputStream inputStream;
        private final BufferedReader reader;
        private final BlockingQueue<StreamLine> lines = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread readerThread;

        StreamLineReader(InputStream inputStream) {
            this.inputStream = inputStream;
            this.reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "llm-stream-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        String readLine(Duration idleTimeout) throws IOException {
            StreamLine next;
            try {
                next = lines.poll(Math.max(1L, idleTimeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("LLM 流式读取被中断", e);
            }
            if (next == null) {
                throw new HttpTimeoutException(
                        "LLM 流式响应超过 " + idleTimeout.toSeconds() + " 秒无数据");
            }
            if (next.error() != null) {
                throw next.error();
            }
            return next.endOfStream() ? null : next.line();
        }

        private void readLoop() {
            try {
                while (!closed.get()) {
                    String line = reader.readLine();
                    if (line == null) {
                        lines.offer(StreamLine.end());
                        return;
                    }
                    lines.offer(StreamLine.data(line));
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    lines.offer(StreamLine.failure(e));
                }
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                // 不调用 BufferedReader.close()：readLine() 持有其内部锁时，跨线程 close
                // 也会等锁。直接关闭底层 HTTP 流会取消订阅并解除阻塞。
                inputStream.close();
            } catch (IOException ignored) {
                // 连接可能已经由 HttpClient 关闭。
            }
            readerThread.interrupt();
        }
    }

    private record StreamLine(String line, IOException error, boolean endOfStream) {
        static StreamLine data(String line) {
            return new StreamLine(line, null, false);
        }

        static StreamLine failure(IOException error) {
            return new StreamLine(null, error, false);
        }

        static StreamLine end() {
            return new StreamLine(null, null, true);
        }
    }

    /** 单次流式响应中的一个 tool_call 分片累积器。 */
    private static final class StreamToolCallAccumulator {
        String id;
        String type = "function";
        String name = "";
        final StringBuilder arguments = new StringBuilder();

        StreamToolCallAccumulator(String id) {
            this.id = id;
        }
    }

    /** 合并一条 chunk 里的 tool_calls 增量（OpenAI 流式：按 index 分段推送）。 */
    private static void mergeStreamToolCalls(JsonNode toolCalls,
                                             Map<Integer, StreamToolCallAccumulator> accumulators) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        for (JsonNode tc : toolCalls) {
            int index = tc.path("index").asInt(0);
            StreamToolCallAccumulator acc = accumulators.computeIfAbsent(index, k -> {
                JsonNode idNode = tc.path("id");
                return new StreamToolCallAccumulator(
                        idNode != null && !idNode.isNull() ? idNode.asText() : null);
            });
            JsonNode function = tc.path("function");
            if (function == null || function.isMissingNode()) {
                continue;
            }
            JsonNode nameNode = function.get("name");
            if (nameNode != null && !nameNode.isNull() && !nameNode.asText().isEmpty()) {
                acc.name = nameNode.asText();
            }
            JsonNode argsNode = function.get("arguments");
            if (argsNode != null && !argsNode.isNull()) {
                acc.arguments.append(argsNode.asText());
            }
        }
    }

    /** 将流式累积结果重建为与全量调用一致的 {@link LLMResponse}。 */
    private LLMResponse buildStreamedResponse(
            StringBuilder content,
            StringBuilder reasoningContent,
            Map<Integer, StreamToolCallAccumulator> accumulators,
            String streamFinishReason) {
        List<LLMResponse.ToolCall> toolCalls = new ArrayList<>();
        if (!accumulators.isEmpty()) {
            // LinkedHashMap 保序（按首次出现 index），无需再排序
            for (StreamToolCallAccumulator acc : accumulators.values()) {
                toolCalls.add(new LLMResponse.ToolCall(
                        acc.id, acc.type, acc.name, acc.arguments.toString()));
            }
        }

        String replyContent = content.length() > 0 ? content.toString() : null;
        String finishReason;
        if (!toolCalls.isEmpty()) {
            finishReason = "tool_calls";
            replyContent = null; // 工具调用轮不暴露正文给下游
        } else if (streamFinishReason != null && !streamFinishReason.isBlank()) {
            finishReason = streamFinishReason;
        } else {
            finishReason = replyContent != null && !replyContent.isBlank() ? "stop" : "stop";
        }

        if (toolCalls.isEmpty() && replyContent != null
                && DsmlToolCallParser.containsMarkup(replyContent)) {
            List<LLMResponse.ToolCall> parsed = DsmlToolCallParser.parse(replyContent, objectMapper);
            if (!parsed.isEmpty()) {
                toolCalls = parsed;
                replyContent = null;
                finishReason = "tool_calls";
            }
        }

        int reasoningLength = reasoningContent.length();
        if (toolCalls.isEmpty() && (replyContent == null || replyContent.isBlank())) {
            log.warn("LLM 流式响应正文为空 | finishReason={} | reasoningLength={}",
                    finishReason, reasoningLength);
        }
        log.debug("流式响应完成 | contentLength={} | reasoningLength={} | toolCalls={}"
                        + " | finishReason={}",
                replyContent != null ? replyContent.length() : 0,
                reasoningLength, toolCalls.size(), finishReason);
        return new LLMResponse(replyContent, toolCalls, finishReason,
                reasoningLength > 0 ? reasoningContent.toString() : null);
    }

    /**
     * 构建含 tools 参数的请求 JSON 体（内部方法）
     */
    private String buildRequestBodyWithTools(List<Message> messages,
                                              List<ToolDefinition> tools) throws Exception {
        return buildRequestBodyWithTools(systemPrompt, messages, tools);
    }

    /**
     * 构建含 tools 参数的请求 JSON 体（默认非流式）
     */
    private String buildRequestBodyWithTools(String systemPrompt, List<Message> messages,
                                              List<ToolDefinition> tools) throws Exception {
        return buildRequestBodyWithTools(systemPrompt, messages, tools, false);
    }

    /**
     * 构建含 tools 参数的请求 JSON 体。{@code stream=true} 时追加 OpenAI 协议流式标记。
     */
    private String buildRequestBodyWithTools(String systemPrompt, List<Message> messages,
                                              List<ToolDefinition> tools,
                                              boolean stream) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());
        // 关闭推理（thinking），加速工具调用决策；推理模型默认会输出大量思考过程，拖慢响应。
        root.put("enable_thinking", false);
        if (stream) {
            root.put("stream", true);
        }

        ArrayNode msgArray = root.putArray("messages");

        // system prompt
        ObjectNode sysNode = msgArray.addObject();
        sysNode.put("role", "system");
        sysNode.put("content", systemPrompt);

        // 消息列表（委托 LLMAdapter 序列化 + 孤立 tool 丢弃兜底）
        msgArray.addAll(llmAdapter.toMessagesNode(messages));

        // tools 定义
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDefinition def : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode func = toolNode.putObject("function");
                func.put("name", def.name());
                func.put("description", def.description());
                func.set("parameters", def.parameters());
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 LLM 响应，支持 tool_calls
     */
    private LLMResponse parseStructuredResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            log.warn("LLM 响应无 choices: {}", responseBody);
            return null;
        }

        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            log.warn("LLM 响应无 message: {}", responseBody);
            return null;
        }

        String finishReason = choices.get(0).has("finish_reason")
                ? choices.get(0).get("finish_reason").asText() : "stop";

        // content（tool_calls 时可能为 null）
        String content = message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText() : null;
        String reasoningContent = message.has("reasoning_content")
                && !message.get("reasoning_content").isNull()
                ? message.get("reasoning_content").asText() : null;

        // tool_calls
        List<LLMResponse.ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = message.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                JsonNode func = tc.get("function");
                if (func != null) {
                    toolCalls.add(new LLMResponse.ToolCall(
                            tc.get("id").asText(),
                            tc.has("type") ? tc.get("type").asText() : "function",
                            func.get("name").asText(),
                            func.get("arguments").asText()));
                }
            }
        }

        // 部分兼容服务不会返回标准 tool_calls，而是把 DSML 工具标记写进 content。
        // 将它恢复成结构化调用，避免内部参数被当作助手正文展示给用户。
        if (toolCalls.isEmpty() && DsmlToolCallParser.containsMarkup(content)) {
            toolCalls = DsmlToolCallParser.parse(content, objectMapper);
            if (!toolCalls.isEmpty()) {
                log.info("已将 content 中的 DSML 转换为 {} 个结构化工具调用", toolCalls.size());
                content = null;
                finishReason = "tool_calls";
            } else {
                // DSML 标记存在但解析失败：清理标记后降级为文本回复
                log.warn("DSML 工具标记解析失败，降级为文本回复");
                if (content != null) {
                    content = content.replaceAll("<" + "｜｜DSML｜｜" + "[^>]*>", "")
                                     .replaceAll("</" + "｜｜DSML｜｜" + "[^>]*>", "")
                                     .trim();
                }
                finishReason = "stop";
            }
        }

        if (toolCalls.isEmpty() && (content == null || content.isBlank())) {
            log.warn("LLM 结构化响应正文为空 | finishReason={} | reasoningLength={}",
                    finishReason, reasoningContent != null ? reasoningContent.length() : 0);
        }

        return new LLMResponse(content, toolCalls, finishReason, reasoningContent);
    }

    /**
     * 截断字符串（日志用）
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 重试支持（P0-3） ====================

    /**
     * 带指数退避的重试执行。
     * 只有 {@link #shouldRetry} 判定为可重试的异常才重试；不可重试（401/400 等）立即抛出。
     */
    private <T> T retryExecute(Supplier<T> supplier, int maxAttempts) {
        int attempt = 0;
        while (true) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                attempt++;
                if (attempt >= maxAttempts || !shouldRetry(cause)) {
                    throw e;
                }
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("LLM 调用失败，第 {}/{} 次重试 | delay={}ms | cause={}",
                        attempt, maxAttempts, delay, cause.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试等待被中断", ie);
                }
            }
        }
    }

    /**
     * 判定异常是否值得重试：
     * <ul>
     *   <li>429 / 5xx —— 服务端过载或临时故障，可重试</li>
     *   <li>网络异常（超时/连接重置/IO）—— 可重试</li>
     *   <li>401 / 400 —— API key 错误或参数/schema 错误，重试无意义，不重试</li>
     * </ul>
     */
    private boolean shouldRetry(Throwable e) {
        if (e == null) return false;
        if (e instanceof LLMHttpException http) {
            return http.statusCode() == 429 || http.statusCode() >= 500;
        }
        return e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.io.IOException
                || e instanceof java.net.ConnectException;
    }

    /** HTTP 非 2xx 时抛异常，触发外层重试逻辑 */
    private void checkHttpStatus(int statusCode) throws LLMHttpException {
        if (statusCode >= 400) {
            throw new LLMHttpException(statusCode, "LLM HTTP " + statusCode);
        }
    }

    /** 返回当前请求线程最近的可诊断失败摘要，不包含密钥或请求正文。 */
    public String getLastFailureSummary() {
        String summary = lastFailureSummary.get();
        return summary == null || summary.isBlank() ? "LLM 返回空" : summary;
    }

    /** 最近失败是否属于重试无意义的凭据/参数错误。 */
    public boolean hasPermanentFailure() {
        return Boolean.TRUE.equals(permanentFailure.get());
    }

    private void recordFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof LLMHttpException http) {
            lastFailureSummary.set("LLM HTTP " + http.statusCode());
            permanentFailure.set(!shouldRetry(http));
        } else if (cause instanceof java.net.http.HttpTimeoutException) {
            lastFailureSummary.set("LLM 请求超时");
            permanentFailure.set(false);
        } else if (cause instanceof java.net.ConnectException) {
            lastFailureSummary.set("LLM 连接失败");
            permanentFailure.set(false);
        } else if (cause instanceof java.io.IOException) {
            lastFailureSummary.set("LLM 网络异常");
            permanentFailure.set(false);
        } else {
            lastFailureSummary.set("LLM 调用失败 ("
                    + cause.getClass().getSimpleName() + ")");
            permanentFailure.set(true);
        }
    }

    private void clearFailure() {
        lastFailureSummary.remove();
        permanentFailure.remove();
    }

    /** LLM HTTP 错误异常，携带状态码供重试分类 */
    private static final class LLMHttpException extends RuntimeException {
        private final int statusCode;

        LLMHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
