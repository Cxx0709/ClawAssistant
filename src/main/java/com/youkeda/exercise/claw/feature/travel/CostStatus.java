package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 成本核算状态。 */
public enum CostStatus {
    /** 尚未核算 */
    NOT_CALCULATED("NOT_CALCULATED"),
    /** 数据已变更，结果过期 */
    STALE("STALE");

    private final String value;

    CostStatus(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值回退未核算（兼容旧存储行） */
    @JsonCreator
    public static CostStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (CostStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return NOT_CALCULATED;
    }
}
