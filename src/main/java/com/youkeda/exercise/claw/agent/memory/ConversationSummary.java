package com.youkeda.exercise.claw.agent.memory;

/**
 * 对话摘要（ADR §9/Phase 3）。
 *
 * @param text           摘要文本（覆盖早期对话）
 * @param coveredUntilSeq 已覆盖到的最大 Turn seq（增量锚点）
 */
public record ConversationSummary(String text, long coveredUntilSeq) {
}
