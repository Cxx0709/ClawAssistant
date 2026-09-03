package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.TurnInitiator;
import com.youkeda.exercise.claw.agent.memory.TurnStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1D 预算裁剪测试：
 * 切在轮次边界、最新 Turn 必含（强制不变式）、unbounded 不裁剪。
 */
class ContextBudgetManagerTest {

    private final TokenEstimator estimator = new HeuristicTokenEstimator();
    private final ContextBudgetManager manager = new ContextBudgetManager(estimator);

    private ConversationTurn turn(String roundId, long seq, Message... msgs) {
        return new ConversationTurn(roundId, seq, TurnStatus.COMPLETED,
                TurnInitiator.USER, List.of(msgs), Instant.now());
    }

    private Message msg(String content) {
        return new Message("user", content);
    }

    @Test
    void unboundedBudgetReturnsAllTurns() {
        List<ConversationTurn> turns = List.of(
                turn("r3", 3, msg("一二三四五六")),
                turn("r2", 2, msg("一二三四五六")),
                turn("r1", 1, msg("一二三四五六")));

        // maxTokens<=0 → 不裁剪
        List<ConversationTurn> result = manager.trimToBudget(turns, 0);
        assertEquals(3, result.size());
        // 引用相同（无拷贝开销）
        assertTrue(result == turns);
    }

    @Test
    void trimKeepsNewestTurnsAndCutAtTurnBoundary() {
        // 每个 Turn 6 个 CJK 字符 ≈ 6 tokens；3 个 Turn ≈ 18 tokens
        List<ConversationTurn> turns = List.of(
                turn("r3", 3, msg("一二三四五六")),
                turn("r2", 2, msg("一二三四五六")),
                turn("r1", 1, msg("一二三四五六")));

        // 预算 12 → 保留最新 2 个（r3 + r2 = 12），裁掉最旧 r1
        List<ConversationTurn> result = manager.trimToBudget(turns, 12);

        assertEquals(2, result.size());
        assertEquals("r3", result.get(0).roundId());
        assertEquals("r2", result.get(1).roundId());
    }

    @Test
    void newestTurnAlwaysIncludedEvenIfOverBudget() {
        // 单个最新 Turn 就超预算（巨大消息 ≈ 200 tokens）→ 仍保留
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append("长");
        List<ConversationTurn> turns = List.of(
                turn("r2", 2, msg(big.toString())),
                turn("r1", 1, msg("短")));

        List<ConversationTurn> result = manager.trimToBudget(turns, 10);

        // 强制不变式：最新 Turn 必含（即使超预算）；更旧的裁掉
        assertEquals(1, result.size());
        assertEquals("r2", result.get(0).roundId());
    }

    @Test
    void emptyListReturnsEmpty() {
        List<ConversationTurn> result = manager.trimToBudget(List.of(), 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void trimIsStableForEqualTokens() {
        // 预算刚好 = 3 个 Turn 总和 → 全保留
        List<ConversationTurn> turns = List.of(
                turn("r3", 3, msg("一二三四五六")),
                turn("r2", 2, msg("一二三四五六")),
                turn("r1", 1, msg("一二三四五六")));
        List<ConversationTurn> result = manager.trimToBudget(turns, 18);
        assertEquals(3, result.size());
    }
}
