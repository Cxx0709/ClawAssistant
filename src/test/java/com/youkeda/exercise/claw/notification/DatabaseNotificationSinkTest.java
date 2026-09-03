package com.youkeda.exercise.claw.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseNotificationSinkTest {

    private DatabaseNotificationSink notifications;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_profile (
                    user_id TEXT PRIMARY KEY,
                    school_id INTEGER,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        notifications = new DatabaseNotificationSink(jdbc, new UserProfileRepository(jdbc),
                new NotificationStreamService(new ObjectMapper()));
        notifications.init();
    }

    @Test
    void notificationsAreDurableAndTenantIsolated() {
        long aliceId = notifications.publish("alice", "TEST", "Alice", "private", 3, null);
        notifications.publish("bob", "TEST", "Bob", "other", 3, null);

        assertEquals(1, notifications.unreadCount("alice"));
        assertEquals("private", notifications.list("alice", 10).get(0).content());
        assertTrue(notifications.markRead("alice", aliceId));
        assertEquals(0, notifications.unreadCount("alice"));
        assertEquals(1, notifications.unreadCount("bob"));
        assertNull(notifications.list("bob", 10).get(0).readAt());
    }
}
