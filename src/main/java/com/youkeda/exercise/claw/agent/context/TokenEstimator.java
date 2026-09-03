package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;

/**
 * Token 估算器（ADR §6）。
 *
 * <p>估算 LLM 上下文的 token 占用，供预算裁剪（{@link ContextBudgetManager}）与溯源使用。
 * 接口现在留定，实现可替换（未来可换 tokenizer 或服务商计数）。
 */
public interface TokenEstimator {

    /** 估算纯文本的 token 数。 */
    int estimate(String text);

    /** 估算单条消息的 token 数（含 reasoning_content）。 */
    int estimate(Message message);

    /** 估算整个 Turn 的 token 数。 */
    int estimateTurn(ConversationTurn turn);
}
