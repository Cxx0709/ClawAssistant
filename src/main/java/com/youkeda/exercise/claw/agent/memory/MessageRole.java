package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 对话消息角色。 */
public enum MessageRole {
    /** 用户消息 */
    USER("user"),
    /** 助手回复，或带 tool_calls 的中间消息 */
    ASSISTANT("assistant"),
    /** 工具调用结果 */
    TOOL("tool"),
    /** 系统消息 */
    SYSTEM("system");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值回退 USER（兼容旧存储行） */
    @JsonCreator
    public static MessageRole fromString(String value) {
        if (value == null) {
            return null;
        }
        for (MessageRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return USER;
    }
}
