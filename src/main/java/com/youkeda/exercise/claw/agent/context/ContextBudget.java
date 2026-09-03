package com.youkeda.exercise.claw.agent.context;

/**
 * Token 预算（ADR §5/§6）。
 *
 * <p>结构先行、算法后置：先定契约（max/used/remaining），
 * 估算算法（字符启发式）与裁剪逻辑在 Phase 1D 实现。
 */
public record ContextBudget(int maxTokens, int usedTokens) {

    public int getRemaining() {
        return Math.max(0, maxTokens - usedTokens);
    }

    /** 未启用预算时的空值（max=0，永不裁剪）。 */
    public static ContextBudget unbounded() {
        return new ContextBudget(0, 0);
    }
}
