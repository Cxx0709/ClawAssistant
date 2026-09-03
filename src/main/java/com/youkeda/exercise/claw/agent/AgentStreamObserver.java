package com.youkeda.exercise.claw.agent;

/**
 * Agent 流式观察者。
 *
 * <p>可选注入到 {@link AgentContext}，供 Web 端流式场景接收 LLM 生成过程中的文本增量，
 * 实现 Web 回复的「打字机」式逐字推送；为 null 时不产生流式副作用。
 */
@FunctionalInterface
public interface AgentStreamObserver {

    /**
     * 收到一段最终会呈现给用户的回复文本增量。
     *
     * @param delta 本次生成的文本片段（可能跨多字节 UTF-8 边界，但不截断，逐 chunk 传入）
     */
    void onContentDelta(String delta);
}
