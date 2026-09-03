package com.youkeda.exercise.claw.agent.model;

/**
 * 任务执行状态，由 Runtime 管理。
 *
 * <p>只记录"执行动作是否完成"，不表示"结果是否令人满意"——后者的判断归 LLM。
 */
public enum ExecutionStatus {

    /** 尚未执行 */
    PENDING,

    /** 正在执行 */
    RUNNING,

    /** 工具执行完成（无论成败） */
    DONE,

    /** 工具调用异常/超时 */
    FAILED,

    /** 被安全检查或策略阻止 */
    BLOCKED
}
