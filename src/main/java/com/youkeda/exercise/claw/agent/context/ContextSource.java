package com.youkeda.exercise.claw.agent.context;

/**
 * Context 来源（ADR §4.3）。
 *
 * <p>不含独立的 "Tool Evidence" 源——工具结果已是 Turn 内的 tool 消息，
 * 随 Turn 保留/裁剪同进同退；被裁掉的轮次由 Summary / LongTermMemory 补救。
 */
public enum ContextSource {
    /** 当前输入所在 Turn（预算紧张也强制包含） */
    CURRENT_TURN,
    /** 最近对话 Turns */
    RECENT_TURNS,
    /** 当前目标状态（AgentGoal/PlanState，预算紧时压缩而非丢弃） */
    PLAN_STATE,
    /** 时间线摘要（Phase 3） */
    SUMMARY,
    /** 长期记忆召回 */
    LONG_TERM_MEMORY,
    /** Skill 知识注入 */
    SKILL_KNOWLEDGE;
}
