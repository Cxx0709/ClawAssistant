package com.youkeda.exercise.claw.notification;

import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.time.Instant;
import java.util.List;

@Service
public class DatabaseNotificationSink implements NotificationSink {

    private final JdbcTemplate jdbc;
    private final UserProfileRepository profiles;
    private final NotificationStreamService streams;
    private final EmailNotificationService emailNotificationService;
    private final LongTermMemoryService longTermMemoryService;

    public DatabaseNotificationSink(JdbcTemplate jdbc,
                                    UserProfileRepository profiles,
                                    NotificationStreamService streams,
                                    EmailNotificationService emailNotificationService,
                                    LongTermMemoryService longTermMemoryService) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.streams = streams;
        this.emailNotificationService = emailNotificationService;
        this.longTermMemoryService = longTermMemoryService;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS notification_outbox (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id        TEXT NOT NULL,
                    source         TEXT NOT NULL,
                    title          TEXT NOT NULL,
                    content        TEXT NOT NULL,
                    cover_url      TEXT,
                    priority       INTEGER NOT NULL DEFAULT 3,
                    action_payload TEXT,
                    status         TEXT NOT NULL DEFAULT 'UNREAD',
                    created_at     INTEGER NOT NULL,
                    read_at        INTEGER
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_notification_user_status
                ON notification_outbox(user_id, status, created_at DESC)
                """);
    }

    @Override
    @Transactional
    public long publish(String userId, String source, String title, String content,
                        int priority, String actionPayload) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("通知缺少收件人");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("通知内容为空");
        profiles.ensureProfile(userId);
        if (!profiles.notificationsEnabled(userId)) return -1;
        long now = System.currentTimeMillis();
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO notification_outbox
                        (user_id, source, title, content, priority, action_payload, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'UNREAD', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, userId);
            statement.setString(2, normalize(source, "SYSTEM"));
            statement.setString(3, normalize(title, "新通知"));
            statement.setString(4, content);
            statement.setInt(5, Math.max(1, Math.min(priority, 5)));
            statement.setString(6, actionPayload);
            statement.setLong(7, now);
            return statement;
        }, keys);
        Number key = keys.getKey();
        long id = key == null ? -1 : key.longValue();
        NotificationRecord record = new NotificationRecord(id, userId,
                normalize(source, "SYSTEM"), normalize(title, "新通知"), content,
                null, Math.max(1, Math.min(priority, 5)), actionPayload, "UNREAD",
                Instant.ofEpochMilli(now), null);
        streams.notify(userId, record);

        // === 邮件旁路通道：站内通知写入成功后，按用户设置投递 ===
        // 邮件发送失败不影响站内通知结果，EmailNotificationService 内部已做异常容错。
        dispatchEmailIfEnabled(userId, source, title, content);

        return id;
    }

    /**
     * 用户开启邮件提醒时投递邮件。优先使用用户资料中绑定的邮箱，
     * 未绑定时回退到长期记忆中识别出的邮箱，以兼容既有用户数据。
     * 任何异常都在 EmailNotificationService 内部捕获，此处不抛出。
     */
    private void dispatchEmailIfEnabled(String userId, String source, String title, String content) {
        try {
            if (!profiles.emailNotificationsEnabled(userId)) {
                return;
            }
            String email = profiles.getEmail(userId);
            if (email == null || email.isBlank()) {
                email = longTermMemoryService.findEmailAddress(userId);
            }
            if (email == null || email.isBlank()) {
                return;
            }
            emailNotificationService.sendNotification(email, title, content, source);
        } catch (Exception e) {
            // 兜底：邮件通道任何异常都不影响主流程
            org.slf4j.LoggerFactory.getLogger(DatabaseNotificationSink.class)
                    .warn("邮件旁路通道异常（已忽略）| userId={} | source={} | error={}",
                            userId, source, e.getMessage());
        }
    }

    @Override
    public int publishToAll(String source, String title, String content,
                            int priority, String actionPayload) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM app_user WHERE enabled = 1", String.class);
        int published = 0;
        for (String userId : ids) {
            if (publish(userId, source, title, content, priority, actionPayload) >= 0) published++;
        }
        return published;
    }

    public List<NotificationRecord> list(String userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT id, user_id, source, title, content, cover_url, priority,
                       action_payload, status, created_at, read_at
                FROM notification_outbox WHERE user_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, rowNum) -> map(rs), userId, safeLimit);
    }

    public int unreadCount(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE user_id = ? AND status = 'UNREAD'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    public boolean markRead(String userId, long id) {
        return jdbc.update("""
                UPDATE notification_outbox SET status = 'READ', read_at = ?
                WHERE id = ? AND user_id = ? AND status = 'UNREAD'
                """, System.currentTimeMillis(), id, userId) > 0;
    }

    public int markAllRead(String userId) {
        return jdbc.update("""
                UPDATE notification_outbox SET status = 'READ', read_at = ?
                WHERE user_id = ? AND status = 'UNREAD'
                """, System.currentTimeMillis(), userId);
    }

    public boolean delete(String userId, long id) {
        return jdbc.update("""
                DELETE FROM notification_outbox WHERE id = ? AND user_id = ?
                """, id, userId) > 0;
    }

    private static NotificationRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long readAt = rs.getLong("read_at");
        boolean unreadTimestamp = rs.wasNull();
        return new NotificationRecord(
                rs.getLong("id"), rs.getString("user_id"), rs.getString("source"),
                rs.getString("title"), rs.getString("content"), rs.getString("cover_url"),
                rs.getInt("priority"), rs.getString("action_payload"), rs.getString("status"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                unreadTimestamp ? null : Instant.ofEpochMilli(readAt));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
