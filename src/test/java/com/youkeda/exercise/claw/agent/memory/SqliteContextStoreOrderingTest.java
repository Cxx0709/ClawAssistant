package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：同一秒内写入的多条消息（如工具调用轮次：assistant tool_calls → tool → assistant）
 * 必须按插入顺序返回。
 *
 * <p>背景：created_at 为秒级时间戳（strftime('%s','now')），工具轮次的三条消息常在同一秒入库，
 * ORDER BY created_at 同值排序不定，导致 tool 消息可能排在它的 tool_calls 消息之前，
 * LLM 接口会以 400 拒绝该序列。修复：排序加 id 决胜键。
 */
class SqliteContextStoreOrderingTest {

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

    /** 直接以相同 created_at 插入，确定性复现「同一秒入库」的场景。 */
    private void insertSameSecond(String userJson) {
        jdbc.update(
                "INSERT INTO context_messages (user_id, message_json, created_at) VALUES ('user-1', ?, 1000)",
                userJson);
    }

    @Test
    void sameSecondMessagesReturnedInInsertionOrder() {
        // 模拟工具调用轮次，插入顺序 = assistant tool_calls → tool → assistant
        insertSameSecond("{\"role\":\"assistant\",\"content\":\"{\\\"action\\\":\\\"estimate\\\"}\","
                + "\"toolCallId\":\"call_1\",\"toolName\":\"didi_ride\"}");
        insertSameSecond("{\"role\":\"tool\",\"content\":\"ok\",\"toolCallId\":\"call_1\"}");
        insertSameSecond("{\"role\":\"assistant\",\"content\":\"价格如下\"}");

        List<Message> history = store.getHistory("user-1", 10);

        assertEquals(3, history.size());
        // 关键断言：tool 消息必须紧跟其 tool_calls 消息之后（即按插入顺序返回）
        assertEquals(MessageRole.ASSISTANT, history.get(0).role());
        assertEquals(MessageRole.TOOL, history.get(1).role());
        assertEquals("call_1", history.get(1).toolCallId());
        assertEquals(MessageRole.ASSISTANT, history.get(2).role());
        assertEquals("价格如下", history.get(2).content());
    }

    /**
     * ADR §7.3/1E 契约：getHistory 返回精确 maxMessages 条，不再向前补取 tool_calls。
     * 工具轮次的原子性由 turn-aware 读取（getTurns）保证；getHistory 是纯 LIMIT 查询。
     */
    @Test
    void getHistoryReturnsExactWindowWithoutBackfill() {
        // 按时间正序插入 21 条：首条是 assistant tool_calls，第二条是 tool 结果，其余为普通对话
        long now = 2000;
        jdbc.update(
                "INSERT INTO context_messages (user_id, message_json, created_at) VALUES ('user-1', ?, ?)",
                "{\"role\":\"assistant\",\"content\":\"{\\\"q\\\":\\\"weather\\\"}\","
                        + "\"toolCallId\":\"call_0\",\"toolName\":\"weather\"}", now++);
        jdbc.update(
                "INSERT INTO context_messages (user_id, message_json, created_at) VALUES ('user-1', ?, ?)",
                "{\"role\":\"tool\",\"content\":\"{result}\",\"toolCallId\":\"call_0\"}", now++);
        for (int i = 0; i < 19; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            jdbc.update(
                    "INSERT INTO context_messages (user_id, message_json, created_at) VALUES ('user-1', ?, ?)",
                    "{\"role\":\"" + role + "\",\"content\":\"filler " + i + "\"}", now++);
        }

        List<Message> history = store.getHistory("user-1", 20);

        // 精确 20 条，不补取（窗口可能以孤立 tool 开头，由调用方负责 turn-aware 读取）
        assertEquals(20, history.size());
        assertEquals(MessageRole.TOOL, history.get(0).role());
        assertEquals("call_0", history.get(0).toolCallId());
    }
}
