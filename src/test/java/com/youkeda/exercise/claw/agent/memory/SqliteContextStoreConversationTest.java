package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteContextStoreConversationTest {

    private SqliteContextStore store;
    private UserExecutionContext executionContext;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE context_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                conversation_id TEXT,
                message_json TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                round_id TEXT,
                seq INTEGER,
                turn_status TEXT,
                turn_initiator TEXT
            )
        """);
        executionContext = new UserExecutionContext();
        store = new SqliteContextStore(
                jdbc, new ObjectMapper(), new StorageProperties(), executionContext);
    }

    @Test
    void isolatesHistoryAndTurnsBetweenConversationsForSameUser() {
        writeTurn("conversation-a", "A 的问题", "A 的回答");
        writeTurn("conversation-b", "B 的问题", "B 的回答");

        try (UserExecutionContext.Scope ignored = executionContext.open("user-1", "conversation-a")) {
            assertEquals(2, store.getHistory(10).size());
            assertEquals("A 的问题", store.getHistory(10).get(0).content());
            assertEquals(1, store.getTurns(10).size());
        }
        try (UserExecutionContext.Scope ignored = executionContext.open("user-1", "conversation-b")) {
            assertEquals(2, store.getHistory(10).size());
            assertEquals("B 的问题", store.getHistory(10).get(0).content());
            assertEquals(1, store.getTurns(10).size());
        }
    }

    private void writeTurn(String conversationId, String question, String answer) {
        try (UserExecutionContext.Scope ignored = executionContext.open("user-1", conversationId)) {
            String roundId = "round-" + conversationId;
            store.beginTurn(roundId, TurnInitiator.USER, new Message("user", question));
            store.appendToTurn(roundId, new Message("assistant", answer));
            store.closeTurn(roundId);
        }
    }
}
