package com.youkeda.exercise.claw.agent.model;

/**
 * LLM 对任务结果的语义评价，由 LLM 管理。
 */
public enum EvaluationState {

    /** LLM 尚未对结果做评估 */
    UNEVALUATED,

    /** LLM 认为结果满足当前任务目标 */
    SATISFACTORY,

    /** LLM 认为结果不满足目标，需要调整或重新执行 */
    UNSATISFACTORY,

    /** 该任务已被 LLM 标记为过时（不再需要） */
    SUPERSEDED
}
