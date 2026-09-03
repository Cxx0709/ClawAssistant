package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 出行方案模式。 */
public enum PlanMode {
    /** 默认均衡模式 */
    BALANCED_DEFAULT("BALANCED_DEFAULT"),
    /** 按优先级偏好 */
    PRIORITY("PRIORITY");

    private final String value;

    PlanMode(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值回退默认均衡模式（兼容旧存储行） */
    @JsonCreator
    public static PlanMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (PlanMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return BALANCED_DEFAULT;
    }
}
