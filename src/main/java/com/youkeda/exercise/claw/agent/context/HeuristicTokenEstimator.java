package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;

/**
 * 字符启发式 Token 估算（ADR §6 的算法后置默认实现）。
 *
 * <p>CJK ≈ 1 token/字符，英文等其他字符 ≈ 1 token/4 字符。
 * 无需外部依赖，成本低；未来可替换为真实 tokenizer。
 */
public class HeuristicTokenEstimator implements TokenEstimator {

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    @Override
    public int estimate(Message message) {
        if (message == null) return 0;
        return estimate(message.content()) + estimate(message.reasoningContent());
    }

    @Override
    public int estimateTurn(ConversationTurn turn) {
        if (turn == null || turn.messages() == null) return 0;
        int total = 0;
        for (Message message : turn.messages()) {
            total += estimate(message);
        }
        return total;
    }
}
