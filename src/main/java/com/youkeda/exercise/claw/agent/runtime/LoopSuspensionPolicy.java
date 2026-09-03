package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.ai.llm.LLMResponse;

import java.util.List;

/**
 * 工具批次执行后的「静默」策略。
 *
 * <p>内核不感知具体业务（如信息猎手后台任务受理）；业务方实现本接口
 * 判定某批工具调用是否触发了「已受理后台任务，本轮无需回复用户」，
 * 由 {@link ExecutionLoop} 统一驱动。
 */
public interface LoopSuspensionPolicy {

    /**
     * 判定一批工具调用是否应静默返回。
     *
     * @param toolCalls   本轮 LLM 发起的工具调用
     * @param toolResults 与 toolCalls 一一对应的执行结果
     * @return true 表示已受理后台任务，本轮应静默返回
     */
    boolean shouldSuspend(List<LLMResponse.ToolCall> toolCalls, List<String> toolResults);
}
