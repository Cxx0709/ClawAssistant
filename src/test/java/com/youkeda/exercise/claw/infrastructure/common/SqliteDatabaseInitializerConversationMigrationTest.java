package com.youkeda.exercise.claw.infrastructure.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SqliteDatabaseInitializerConversationMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyMessagesWithoutExpiringThem() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("migration.db");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE context_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                message_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """);
        long oldTimestamp = System.currentTimeMillis() / 1000 - 30L * 86400;
        jdbc.update("""
            INSERT INTO context_messages (user_id, message_json, created_at)
            VALUES ('legacy-user', '{"role":"user","content":"旧对话"}', ?)
        """, oldTimestamp);

        SqliteDatabaseInitializer initializer = new SqliteDatabaseInitializer(jdbc);
        Field field = SqliteDatabaseInitializer.class.getDeclaredField("datasourceUrl");
        field.setAccessible(true);
        field.set(initializer, url);
        initializer.init();

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE user_id = 'legacy-user'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM context_messages WHERE user_id = 'legacy-user'", Integer.class));
        assertNotNull(jdbc.queryForObject("""
            SELECT conversation_id FROM context_messages WHERE user_id = 'legacy-user'
        """, String.class));
        assertEquals("旧对话", jdbc.queryForObject("""
            SELECT content FROM chat_messages WHERE user_id = 'legacy-user' AND role = 'user'
        """, String.class));
        assertEquals("旧对话", jdbc.queryForObject("""
            SELECT title FROM conversations WHERE user_id = 'legacy-user'
        """, String.class));
        dataSource.destroy();
    }
}
