package com.youkeda.exercise.claw.web.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationServiceTest {

    private ConversationService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL,
                title_source TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0, last_message_preview TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                last_message_at INTEGER, metadata_updated_at INTEGER, deleted_at INTEGER
            )
        """);
        jdbc.execute("CREATE VIRTUAL TABLE chat_message_search USING fts5(message_id, conversation_id, user_id, content)");
        jdbc.execute("CREATE TABLE chat_messages (user_id TEXT, conversation_id TEXT)");
        jdbc.execute("CREATE TABLE chat_runs (user_id TEXT, conversation_id TEXT)");
        jdbc.execute("""
            CREATE TABLE context_messages (
                id INTEGER PRIMARY KEY, user_id TEXT, conversation_id TEXT, message_json TEXT
            )
        """);
        jdbc.execute("CREATE TABLE conversation_summaries (user_id TEXT, conversation_id TEXT)");
        jdbc.execute("CREATE TABLE conversation_agent_plans (user_id TEXT, conversation_id TEXT)");
        jdbc.execute("CREATE TABLE conversation_travel_plans (user_id TEXT, conversation_id TEXT)");
        jdbc.execute("CREATE TABLE conversation_skill_sessions (user_id TEXT, conversation_id TEXT)");
        service = new ConversationService(jdbc);
    }

    @Test
    void createsTitlesListsAndEnforcesOwnership() {
        Conversation conversation = service.create("user-a");
        service.touchAfterMessage("user-a", conversation.id(), "  帮我\n规划杭州两日游  ");

        Conversation titled = service.requireOwned("user-a", conversation.id());
        assertEquals("规划杭州两日游", titled.title());
        assertEquals(1, service.list("user-a", false, 50).size());
        assertThrows(ResponseStatusException.class,
                () -> service.requireOwned("user-b", conversation.id()));

        Conversation pinned = service.update(
                "user-a", conversation.id(), "杭州周末", true, null);
        assertEquals("杭州周末", pinned.title());
        assertTrue(pinned.pinned());
    }

    @Test
    void searchesMessageContentAndMovesDeletedConversationToTrash() {
        Conversation conversation = service.create("user-a");
        service.touchAfterMessage("user-a", conversation.id(), "杭州路线");
        jdbc.update("""
            INSERT INTO chat_message_search(message_id, conversation_id, user_id, content)
            VALUES ('m1', ?, 'user-a', '西湖')
        """, conversation.id());

        assertEquals(conversation.id(),
                service.page("user-a", false, false, "西湖", null, 20).items().get(0).id());

        Conversation archived = service.update("user-a", conversation.id(), null, true, true);
        assertTrue(archived.archived());
        assertFalse(archived.pinned());

        service.delete("user-a", conversation.id());
        assertTrue(service.page("user-a", false, false, null, null, 20).items().isEmpty());
        assertEquals(conversation.id(),
                service.page("user-a", false, true, null, null, 20).items().get(0).id());
    }
}
