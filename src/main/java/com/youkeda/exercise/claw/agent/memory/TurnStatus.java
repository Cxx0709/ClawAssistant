package com.youkeda.exercise.claw.agent.memory;

/**
 * Turn 生命周期状态。
 *
 * <p>对应 ADR（docs/agent-context-architecture.md）§3.4/§7：
 * <b>显式维护，不推导</b>——beginTurn→RUNNING、closeTurn→COMPLETED、
 * catch→markIncomplete、启动扫描 RUNNING 超时→INCOMPLETE。
 * 推导（读末条消息判状态）仅用于存量数据一次性回填。
 */
public enum TurnStatus {
    /** 正在执行（未闭环） */
    RUNNING,
    /** 正常闭环（最后一条为 assistant 文本回复） */
    COMPLETED,
    /** 未正常闭环（崩溃残留 / 超时 / 异常中断），不进窗口 */
    INCOMPLETE;
}
