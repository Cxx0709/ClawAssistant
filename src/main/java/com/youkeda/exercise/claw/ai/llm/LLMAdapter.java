package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LLM 协议序列化器（ADR §4.1 的 LLMAdapter）。
 *
 * <p>把 {@link Message} 列表转成 OpenAI 协议的 messages JSON 节点。
 * 从 {@link LLMClient} 抽离，使协议序列化独立可测、可复用。
 *
 * <p>ADR §7.3/1E：孤立 tool 丢弃兜底已退役——主路径用 Turn 切割读取
 * （{@code getTurns} 以轮次为原子单位，不产生孤立 tool），无需此兜底。
 *
 * <p>非 Spring bean，由 {@link LLMClient} 构造时内部创建（保持 LLMClient 构造签名不变）。
 */
public class LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(LLMAdapter.class);

    private final ObjectMapper objectMapper;

    public LLMAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把 Message 列表序列化为 LLM 协议 messages 数组节点。
     */
    public ArrayNode toMessagesNode(List<Message> messages) {
        ArrayNode msgArray = objectMapper.createArrayNode();
        for (Message msg : messages) {
            msgArray.add(serializeMessage(msg));
        }
        return msgArray;
    }

    /**
     * 将单条 Message 序列化为 LLM 协议的 JSON 节点。
     * <p>按 role 分三种序列化策略：
     * <ul>
     *   <li>{@code "user"} — 常规内容</li>
     *   <li>{@code "assistant"} — 可能携带 {@code tool_calls}</li>
     *   <li>{@code "tool"} — 工具调用结果，带 {@code tool_call_id}</li>
     * </ul>
     */
    public ObjectNode serializeMessage(Message msg) {
        ObjectNode node = objectMapper.createObjectNode();

        switch (msg.role()) {
            case USER -> {
                node.put("role", "user");
                node.put("content", msg.content() != null ? msg.content() : "");
            }
            case ASSISTANT -> {
                node.put("role", "assistant");
                if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                    node.put("reasoning_content", msg.reasoningContent());
                }
                if (msg.isToolCall()) {
                    node.putNull("content");
                    ArrayNode tcs = node.putArray("tool_calls");

                    String tcId = msg.toolCallId();
                    if (tcId != null && tcId.contains(",")) {
                        // 多 tool_call：解析逗号分隔的 ID 列表和 JSON 数组参数
                        String[] ids = tcId.split(",", -1);
                        String[] names = msg.toolName() != null
                                ? msg.toolName().split(",", -1)
                                : new String[ids.length];
                        JsonNode argsArray;
                        try {
                            argsArray = objectMapper.readTree(msg.content());
                        } catch (Exception e) {
                            log.warn("多 tool_call 参数解析失败: {}", e.getMessage());
                            // 降级：回退到单 tool_call 逻辑
                            ObjectNode tc = tcs.addObject();
                            tc.put("id", ids[0].trim());
                            tc.put("type", "function");
                            ObjectNode func = tc.putObject("function");
                            func.put("name", msg.toolName());
                            func.put("arguments", msg.content());
                            break;
                        }

                        for (int i = 0; i < ids.length; i++) {
                            ObjectNode tc = tcs.addObject();
                            tc.put("id", ids[i].trim());
                            tc.put("type", "function");
                            ObjectNode func = tc.putObject("function");
                            func.put("name", i < names.length ? names[i].trim() : "unknown");
                            JsonNode argNode = i < argsArray.size() ? argsArray.get(i) : null;
                            func.put("arguments", argNode != null
                                    ? (argNode instanceof TextNode ? ((TextNode) argNode).asText() : argNode.toString())
                                    : "{}");
                        }
                    } else {
                        // 单 tool_call
                        ObjectNode tc = tcs.addObject();
                        tc.put("id", tcId);
                        tc.put("type", "function");
                        ObjectNode func = tc.putObject("function");
                        func.put("name", msg.toolName());
                        func.put("arguments", msg.content());
                    }
                } else {
                    node.put("content", msg.content() != null ? msg.content() : "");
                }
            }
            case TOOL -> {
                node.put("role", "tool");
                node.put("content", msg.content() != null ? msg.content() : "");
                node.put("tool_call_id", msg.toolCallId());
            }
            case SYSTEM -> {
                node.put("role", "system");
                node.put("content", msg.content() != null ? msg.content() : "");
            }
        }
        return node;
    }
}
