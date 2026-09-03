package com.youkeda.exercise.claw.agent.memory;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Agent Run 的原子单位（ADR §3.2/§3.4）。
 *
 * <p>一次交互执行（用户消息到达 → Agent 执行 → 回复闭环）的最小不可分单位。
 * 窗口切割、Summary 锚点、目标引用均以 Turn 为界——绝不切开一个 Turn。
 *
 * <p>Turn 不强制以 user 消息开头：系统触发的 Run（定时任务/通知推送）
 * 同样构成一个 Turn（{@link TurnInitiator#SYSTEM}）。
 *
 * @param roundId   全局唯一轮次 ID（UUID）
 * @param seq       每用户全局单调序号（Summary 锚点/窗口管理/debug 都要它）
 * @param status    显式维护的轮次状态（{@link TurnStatus}）
 * @param initiator 发起方（用户消息 / 系统触发）
 * @param messages  轮内消息（正序，含 user/assistant/tool）
 * @param createdAt 轮次创建时间
 */
public record ConversationTurn(
        String roundId,
        long seq,
        TurnStatus status,
        TurnInitiator initiator,
        List<Message> messages,
        Instant createdAt) {

    public ConversationTurn {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 最后一条是否为 tool_calls（INCOMPLETE 的典型残留形态）。
     * 供启动恢复扫描与回填推导使用。
     */
    public boolean endsWithUnresolvedToolCall() {
        if (messages.isEmpty()) return false;
        Message last = messages.get(messages.size() - 1);
        return last.role() == MessageRole.ASSISTANT && last.isToolCall();
    }
}
