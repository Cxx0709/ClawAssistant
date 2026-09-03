package com.youkeda.exercise.claw.agent.memory;

/**
 * 对话摘要存储（ADR §9/Phase 3）。
 *
 * <p>单用户单行：当前对话的增量摘要，以 {@code coveredUntilSeq} 标记已覆盖到的轮次。
 */
public interface ConversationSummaryStore {

    /** 获取当前摘要，没有则返回 null */
    ConversationSummary get();

    /** 保存（创建或更新）摘要 */
    void save(ConversationSummary summary);

    /** 清除摘要 */
    void clear();
}
