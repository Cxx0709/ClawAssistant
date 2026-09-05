package com.youkeda.exercise.claw.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseNotificationSinkTest {

    private DatabaseNotificationSink notifications;
    private UserProfileRepository profiles;
    private EmailNotificationService emailService;
    private LongTermMemoryService memories;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_profile (
                    user_id TEXT PRIMARY KEY,
                    school_id INTEGER,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    email TEXT,
                    email_notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        profiles = new UserProfileRepository(jdbc);
        emailService = mock(EmailNotificationService.class);
        memories = mock(LongTermMemoryService.class);
        notifications = new DatabaseNotificationSink(jdbc, profiles,
                new NotificationStreamService(new ObjectMapper()),
                emailService, memories);
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

    @Test
    void emailUsesBoundProfileAddress() {
        profiles.setEmail("alice", "alice@example.com");

        notifications.publish("alice", "TEST", "Reminder", "Do the thing", 3, null);

        verify(emailService).sendNotification("alice@example.com", "Reminder", "Do the thing", "TEST");
        verify(memories, never()).findEmailAddress("alice");
    }

    @Test
    void disabledEmailNotificationsDoNotSend() {
        profiles.setEmail("alice", "alice@example.com");
        profiles.setEmailNotificationsEnabled("alice", false);

        notifications.publish("alice", "TEST", "Reminder", "Do the thing", 3, null);

        verify(emailService, never()).sendNotification(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(memories, never()).findEmailAddress("alice");
    }

    @Test
    void emailFallsBackToLongTermMemory() {
        when(memories.findEmailAddress("alice")).thenReturn("remembered@example.com");

        notifications.publish("alice", "TEST", "Reminder", "Do the thing", 3, null);

        verify(emailService).sendNotification("remembered@example.com", "Reminder", "Do the thing", "TEST");
    }
}
