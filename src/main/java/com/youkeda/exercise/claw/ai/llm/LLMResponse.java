package com.youkeda.exercise.claw.ai.llm;

import java.util.Collections;
import java.util.List;

/**
 * LLM 调用返回结果
 *
 * <p>根据 {@code finishReason} 区分三种类型：
 * <ul>
 *   <li>{@code "stop"} — 文本回复，读取 {@link #getContent()}</li>
 *   <li>{@code "tool_calls"} — LLM 要求调用工具，读取 {@link #getToolCalls()}</li>
 *   <li>{@code "plan"} — LLM 产出了结构化计划，读取 {@link #getPlan()}</li>
 * </ul>
 */
public class LLMResponse {

    private final String content;
    private final List<ToolCall> toolCalls;
    private final PlanDecision plan;
    private final String finishReason;
    private final String reasoningContent;

    public LLMResponse(String content, List<ToolCall> toolCalls, String finishReason) {
        this(content, toolCalls, null, finishReason, null);
    }

    public LLMResponse(String content, List<ToolCall> toolCalls,
                       String finishReason, String reasoningContent) {
        this(content, toolCalls, null, finishReason, reasoningContent);
    }

    public LLMResponse(String content, List<ToolCall> toolCalls, PlanDecision plan,
                       String finishReason, String reasoningContent) {
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : List.of();
        this.plan = plan;
        this.finishReason = finishReason;
        this.reasoningContent = reasoningContent;
    }

    /**
     * LLM 是否要求调用工具
     */
    public boolean isToolCall() {
        return "tool_calls".equals(finishReason) && !toolCalls.isEmpty();
    }

    /**
     * LLM 是否产出了结构化计划
     */
    public boolean isPlan() {
        return "plan".equals(finishReason) && plan != null;
    }

    // ==================== Getters ====================

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    public PlanDecision getPlan() {
        return plan;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    // ==================== ToolCall ====================

    /**
     * 一次工具调用请求
     *
     * @param id        工具调用 ID，用于关联 tool result
     * @param type      调用类型，固定为 "function"
     * @param name      函数名
     * @param arguments 参数字符串（JSON 格式）
     */
    public record ToolCall(String id, String type, String name, String arguments) {
        public ToolCall(String id, String name, String arguments) {
            this(id, "function", name, arguments);
        }
    }
}
