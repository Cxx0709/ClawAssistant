package com.youkeda.exercise.claw.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 3 对话摘要存储测试：save→get 往返、clear、空表返回 null。
 */
class SqliteConversationSummaryStoreTest {

    private SqliteConversationSummaryStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE conversation_summary (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                summary_text TEXT NOT NULL,
                covered_until_seq INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);
        store = new SqliteConversationSummaryStore(jdbc);
    }

    @Test
    void emptyTableReturnsNull() {
        assertNull(store.get());
    }

    @Test
    void saveThenGetRoundTrips() {
        store.save(new ConversationSummary("用户想规划旅游，偏好低价酒店", 15));

        ConversationSummary loaded = store.get();
        assertEquals("用户想规划旅游，偏好低价酒店", loaded.text());
        assertEquals(15, loaded.coveredUntilSeq());
    }

    @Test
    void saveOverwritesSingleRow() {
        store.save(new ConversationSummary("第一版摘要", 5));
        store.save(new ConversationSummary("第二版摘要", 12));

        ConversationSummary loaded = store.get();
        assertEquals("第二版摘要", loaded.text());
        assertEquals(12, loaded.coveredUntilSeq());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_summary", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void clearRemovesRow() {
        store.save(new ConversationSummary("摘要", 3));
        store.clear();
        assertNull(store.get());
    }
}
