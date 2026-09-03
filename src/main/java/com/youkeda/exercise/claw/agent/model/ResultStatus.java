package com.youkeda.exercise.claw.agent.model;

/**
 * 工具执行结果的原始状态，由 Runtime 根据工具返回值写入。
 *
 * <p>区别于 {@link ExecutionStatus}——ExecutionStatus 是 Runtime 对执行动作的记录，
 * ResultStatus 是工具自身返回的语义（成功/失败/部分成功等）。
 */
public enum ResultStatus {
    SUCCESS,
    FAILED,
    PARTIAL,
    BLOCKED,
    /** 工具返回结果无法解析（非 JSON 或格式异常）——解析失败 ≠ 工具失败，标记未知（P0-4 fail-closed） */
    UNKNOWN
}
