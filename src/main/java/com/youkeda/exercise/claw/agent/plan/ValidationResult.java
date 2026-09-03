package com.youkeda.exercise.claw.agent.plan;

import java.util.List;

/**
 * Plan 校验结果。
 *
 * @param valid   plan 是否合法
 * @param errors  硬错误（非法）
 * @param warnings 软警告（可选调整）
 */
public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
}
