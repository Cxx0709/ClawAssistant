package com.youkeda.exercise.claw.agent;

/**
 * Agent 执行器接口
 *
 * 职责：接收 AgentContext，协调整个 Agent 执行流程，返回回复文本。
 */
public interface AgentExecutor {

    /**
     * 执行 Agent 调用
     *
     * @param context 执行上下文（消息、用户、意图等）
     * @return 回复文本
     */
    String execute(AgentContext context);
}