package com.youkeda.exercise.claw.feature.budget;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 方案与预算的对比状态。 */
public enum BudgetStatus {
    /** 无预算上限 */
    NO_LIMIT("NO_LIMIT"),
    /** 预算内 */
    WITHIN_BUDGET("WITHIN_BUDGET"),
    /** 超出预算 */
    OVER_BUDGET("OVER_BUDGET"),
    /** 区间跨越预算，可能超出 */
    POSSIBLY_OVER_BUDGET("POSSIBLY_OVER_BUDGET"),
    /** 信息不足，无法判定 */
    INDETERMINATE("INDETERMINATE");

    private final String value;

    BudgetStatus(String value) {
        this.value = value;
    }

    /** 序列化值（LLM 契约字符串，保持不变） */
    @JsonValue
    public String value() {
        return value;
    }

    /** 反序列化：未知值返回 null */
    @JsonCreator
    public static BudgetStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (BudgetStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
