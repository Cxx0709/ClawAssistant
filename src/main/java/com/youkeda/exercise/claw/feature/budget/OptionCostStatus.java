package com.youkeda.exercise.claw.feature.budget;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 单个方案成本核算状态。 */
public enum OptionCostStatus {
    /** 核算完整 */
    SUCCESS("SUCCESS"),
    /** 存在缺失价格等，仅部分核算 */
    PARTIAL("PARTIAL");

    private final String value;

    OptionCostStatus(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值返回 null */
    @JsonCreator
    public static OptionCostStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (OptionCostStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
