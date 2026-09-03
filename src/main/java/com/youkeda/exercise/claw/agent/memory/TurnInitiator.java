package com.youkeda.exercise.claw.agent.memory;

/**
 * Turn 的发起方。
 *
 * <p>ADR §3.4 约定：Turn 不强制以 user 消息开头——
 * 定时任务/通知推送触发的 Agent Run（如 {@code AgentTaskExecutor}）同样是 Turn。
 */
public enum TurnInitiator {
    /** 用户消息触发（主路径） */
    USER,
    /** 系统触发（定时任务、通知推送等，无用户消息也成轮） */
    SYSTEM;
}
