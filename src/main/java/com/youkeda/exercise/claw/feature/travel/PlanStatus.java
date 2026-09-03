package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 候选方案状态。 */
public enum PlanStatus {
    /** 待确认候选方案 */
    CANDIDATE("CANDIDATE"),
    /** 已修改，待复核 */
    NEEDS_REVIEW("NEEDS_REVIEW");

    private final String value;

    PlanStatus(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值回退待确认候选（兼容旧存储行） */
    @JsonCreator
    public static PlanStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (PlanStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return CANDIDATE;
    }
}
