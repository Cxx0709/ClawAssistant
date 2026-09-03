package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.TurnInitiator;
import com.youkeda.exercise.claw.agent.memory.TurnStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1A 接口冻结契约测试。
 *
 * <p>锁定 ADR 领域模型的形状与防御性拷贝语义，
 * 防止 1B/1C 实现阶段无意改动已冻结契约。
 */
class ContextModelContractTest {

    @Test
    void turnStatusStatesExplicitlyManaged() {
        // ADR §3.4：显式状态 = RUNNING / COMPLETED / INCOMPLETE，无推导用的 CREATED/FAILED
        TurnStatus[] states = TurnStatus.values();
        assertEquals(3, states.length);
        assertEquals(TurnStatus.RUNNING, states[0]);
        assertEquals(TurnStatus.COMPLETED, states[1]);
        assertEquals(TurnStatus.INCOMPLETE, states[2]);
    }

    @Test
    void turnCopiesMessagesDefensively() {
        List<Message> mutable = new ArrayList<>();
        mutable.add(new Message("user", "你好"));
        ConversationTurn turn = new ConversationTurn("round-1", 1,
                TurnStatus.COMPLETED, TurnInitiator.USER, mutable, Instant.now());

        mutable.add(new Message("user", "注入"));

        assertEquals(1, turn.messages().size());
        assertNotSame(turn.messages(), mutable);
        assertFalse(turn.messages().get(0).content().equals("注入"));
    }

    @Test
    void turnDetectsUnresolvedToolCallTail() {
        Message toolCall = new Message("assistant", "{\"query\":\"x\"}",
                null, null, null, "call_1", "search");
        ConversationTurn incomplete = new ConversationTurn("round-2", 2,
                TurnStatus.INCOMPLETE, TurnInitiator.USER, List.of(toolCall), Instant.now());
        assertTrue(incomplete.endsWithUnresolvedToolCall());

        ConversationTurn done = new ConversationTurn("round-3", 3,
                TurnStatus.COMPLETED, TurnInitiator.USER,
                List.of(new Message("assistant", "好的")), Instant.now());
        assertFalse(done.endsWithUnresolvedToolCall());
    }

    @Test
    void systemTriggeredTurnIsValid() {
        // ADR §3.4：系统触发的 Run（定时任务/通知推送）同样构成 Turn
        ConversationTurn systemTurn = new ConversationTurn("round-4", 4,
                TurnStatus.RUNNING, TurnInitiator.SYSTEM, List.of(), Instant.now());
        assertEquals(TurnInitiator.SYSTEM, systemTurn.initiator());
        assertTrue(systemTurn.messages().isEmpty());
    }

    @Test
    void builderResultCopiesMessagesDefensively() {
        List<Message> mutable = new ArrayList<>();
        mutable.add(new Message("user", "你好"));
        ContextBuilder.Result result = new ContextBuilder.Result(
                mutable, ContextMetadata.empty(), List.of(), null,
                ContextBudget.unbounded(), 0);

        mutable.clear();

        assertEquals(1, result.messages().size());
        assertNotSame(result.messages(), mutable);
    }

    @Test
    void budgetRemainingIsClampedToZero() {
        ContextBudget over = new ContextBudget(10, 15);
        assertEquals(0, over.getRemaining());
        assertEquals(5, new ContextBudget(10, 5).getRemaining());
        assertEquals(0, ContextBudget.unbounded().getRemaining());
    }

    @Test
    void metadataNormalizesNullSources() {
        ContextMetadata metadata = new ContextMetadata("round-1", null);
        assertTrue(metadata.sources().isEmpty());
        assertEquals("round-1", metadata.turnId());
    }
}
