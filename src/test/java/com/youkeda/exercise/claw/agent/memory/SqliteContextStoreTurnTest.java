package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1B Turn 维度读写测试。
 *
 * <p>覆盖：beginTurn/appendToTurn/closeTurn 同轮 round_id 一致、seq 递增；
 * getTurns 按 Turn 切割、过滤 INCOMPLETE、不切破轮次；
 * 存量数据回填（round_id 相邻成组 + INCOMPLETE 判定）。
 */
class SqliteContextStoreTurnTest {

    private SqliteContextStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE context_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                message_json TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                round_id TEXT,
                seq INTEGER,
                turn_status TEXT,
                turn_initiator TEXT
            )
        """);

        store = new SqliteContextStore(jdbc, new ObjectMapper(), new StorageProperties(),
                new UserExecutionContext());
    }

    /** 直接以相同 created_at 插入存量数据（round_id 为 NULL，模拟迁移前数据）。 */
    private void insertLegacy(String json, long createdAt) {
        jdbc.update("""
            INSERT INTO context_messages (user_id, message_json, created_at)
            VALUES ('user-1', ?, ?)
        """, json, createdAt);
    }

    @Test
    void beginAndCloseTurnGroupMessagesConsistently() {
        long seq1 = store.beginTurn("user-1", "round-1", TurnInitiator.USER,
                new Message("user", "查一下天气"));
        store.appendToTurn("user-1", "round-1",
                new Message("assistant", "{\"action\":\"weather\"}", null, null, null, "call_1", "weather"));
        store.appendToTurn("user-1", "round-1",
                new Message("tool", "晴天", null, null, null, "call_1", null));
        store.appendToTurn("user-1", "round-1", new Message("assistant", "今天是晴天"));
        store.closeTurn("user-1", "round-1");

        List<ConversationTurn> turns = store.getTurns("user-1", 10);
        assertEquals(1, turns.size());

        ConversationTurn turn = turns.get(0);
        assertEquals("round-1", turn.roundId());
        assertEquals(TurnStatus.COMPLETED, turn.status());
        assertEquals(TurnInitiator.USER, turn.initiator());
        assertEquals(seq1, turn.seq());
        assertEquals(4, turn.messages().size());
        // 消息按时间正序
        assertEquals(MessageRole.USER, turn.messages().get(0).role());
        assertEquals(MessageRole.ASSISTANT, turn.messages().get(1).role());
        assertEquals("call_1", turn.messages().get(1).toolCallId());
        assertEquals(MessageRole.TOOL, turn.messages().get(2).role());
        assertEquals(MessageRole.ASSISTANT, turn.messages().get(3).role());
    }

    @Test
    void multipleTurnsReturnedNewestFirstWithMonotonicSeq() {
        store.beginTurn("user-1", "round-1", TurnInitiator.USER, new Message("user", "你好"));
        store.closeTurn("user-1", "round-1");
        store.beginTurn("user-1", "round-2", TurnInitiator.USER, new Message("user", "再见"));
        store.closeTurn("user-1", "round-2");

        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        assertEquals(2, turns.size());
        // 最新在前
        assertEquals("round-2", turns.get(0).roundId());
        assertEquals("round-1", turns.get(1).roundId());
        // seq 递增且 round-2 > round-1
        assertTrue(turns.get(0).seq() > turns.get(1).seq());
    }

    @Test
    void incompleteTurnsExcludedFromWindow() {
        // round-1 正常闭环
        store.beginTurn("user-1", "round-1", TurnInitiator.USER, new Message("user", "你好"));
        store.closeTurn("user-1", "round-1");
        // round-2 异常中断（写了 tool_calls 未闭环）
        store.beginTurn("user-1", "round-2", TurnInitiator.USER, new Message("user", "查一下"));
        store.appendToTurn("user-1", "round-2",
                new Message("assistant", "{\"q\":\"x\"}", null, null, null, "call_9", "search"));
        store.markTurnIncomplete("user-1", "round-2");

        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        // INCOMPLETE 的 round-2 不进窗口
        assertEquals(1, turns.size());
        assertEquals("round-1", turns.get(0).roundId());
    }

    @Test
    void getTurnsDoesNotCutAcrossToolRound() {
        // 用 beginTurn 写入一个完整工具轮次：assistant(tool_calls) → tool → assistant
        store.beginTurn("user-1", "round-1", TurnInitiator.USER, new Message("user", "订个滴滴"));
        store.appendToTurn("user-1", "round-1",
                new Message("assistant", "{\"action\":\"didi\"}", null, null, null, "call_2", "didi_ride"));
        store.appendToTurn("user-1", "round-1",
                new Message("tool", "价格200", null, null, null, "call_2", null));
        store.appendToTurn("user-1", "round-1", new Message("assistant", "已帮你叫好"));
        store.closeTurn("user-1", "round-1");

        // 读 1 个 Turn，窗口应包含整个工具轮次（tool_calls + tool 结果都在）
        List<ConversationTurn> turns = store.getTurns("user-1", 1);
        assertEquals(1, turns.size());
        ConversationTurn turn = turns.get(0);
        // 4 条消息完整保留，无孤立 tool
        assertEquals(4, turn.messages().size());
        assertEquals(MessageRole.TOOL, turn.messages().get(2).role());
        assertEquals("call_2", turn.messages().get(2).toolCallId());
    }

    @Test
    void backfillGroupsSameSecondLegacyRows() {
        // 存量数据：同 created_at 的一组（assistant tool_calls → tool → assistant）
        insertLegacy("{\"role\":\"assistant\",\"content\":\"{\\\"action\\\":\\\"estimate\\\"}\","
                + "\"toolCallId\":\"call_1\",\"toolName\":\"didi_ride\"}", 1000);
        insertLegacy("{\"role\":\"tool\",\"content\":\"ok\",\"toolCallId\":\"call_1\"}", 1000);
        insertLegacy("{\"role\":\"assistant\",\"content\":\"价格如下\"}", 1000);

        // 触发回填 + 读取
        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        // 同一 created_at 成一组 → 1 个 Turn
        assertEquals(1, turns.size());
        ConversationTurn turn = turns.get(0);
        assertEquals(TurnStatus.COMPLETED, turn.status());
        assertEquals(3, turn.messages().size());
        // 顺序 = 插入顺序：tool_calls 在 tool 前
        assertEquals(MessageRole.ASSISTANT, turn.messages().get(0).role());
        assertEquals("call_1", turn.messages().get(0).toolCallId());
        assertEquals(MessageRole.TOOL, turn.messages().get(1).role());
    }

    @Test
    void backfillMarksUnresolvedToolCallTailIncomplete() {
        // 存量数据：一个工具轮次只写了 tool_calls，没有 tool 结果（崩溃残留）
        insertLegacy("{\"role\":\"assistant\",\"content\":\"{\\\"q\\\":\\\"weather\\\"}\","
                + "\"toolCallId\":\"call_5\",\"toolName\":\"weather\"}", 3000);

        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        // 回填判定为 INCOMPLETE → 不进窗口
        assertEquals(0, turns.size());
    }

    @Test
    void backfillKeepsSeparateSecondsAsSeparateTurns() {
        // 不同秒的两条消息 → 两个独立 Turn
        insertLegacy("{\"role\":\"user\",\"content\":\"你好\"}", 1000);
        insertLegacy("{\"role\":\"user\",\"content\":\"再见\"}", 2000);

        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        assertEquals(2, turns.size());
        assertNotEquals(turns.get(0).roundId(), turns.get(1).roundId());
    }

    @Test
    void staleRunningTurnRecoveredToIncomplete() {
        // 崩溃残留：超时（created_at 很久以前）的 RUNNING Turn
        store.beginTurn("user-1", "round-stale", TurnInitiator.USER, new Message("user", "挂了"));
        jdbc.update("""
            UPDATE context_messages SET created_at = 1
            WHERE user_id = 'user-1' AND round_id = 'round-stale'
        """);
        // 一个新鲜的正常 Turn
        store.beginTurn("user-1", "round-fresh", TurnInitiator.USER, new Message("user", "你好"));
        store.closeTurn("user-1", "round-fresh");

        List<ConversationTurn> turns = store.getTurns("user-1", 10);

        // 超时 RUNNING → 恢复扫描转 INCOMPLETE → 不进窗口；新鲜 Turn 正常返回
        assertEquals(1, turns.size());
        assertEquals("round-fresh", turns.get(0).roundId());
    }
}
