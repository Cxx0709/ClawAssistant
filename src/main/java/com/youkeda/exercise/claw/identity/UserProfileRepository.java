package com.youkeda.exercise.claw.identity;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Channel-neutral user preferences. */
@Repository
public class UserProfileRepository {

    private final JdbcTemplate jdbc;

    public UserProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_profile (
                    user_id                     TEXT PRIMARY KEY,
                    notifications_enabled       INTEGER NOT NULL DEFAULT 1,
                    email                       TEXT,
                    email_notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    created_at                  INTEGER NOT NULL,
                    updated_at                  INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES app_user(id)
                )
                """);
        // 兼容已有数据库：增量添加新列（SQLite 忽略已存在的列）
        addColumnIfMissing("user_profile", "email", "TEXT");
        addColumnIfMissing("user_profile", "email_notifications_enabled", "INTEGER NOT NULL DEFAULT 1");
    }

    /** SQLite 没有 ADD COLUMN IF NOT EXISTS，用 PRAGMA 探测后再 ALTER。 */
    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = !jdbc.queryForList(
                "SELECT name FROM pragma_table_info(?) WHERE name = ?",
                String.class, table, column).isEmpty();
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public void ensureProfile(String userId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT OR IGNORE INTO user_profile
                    (user_id, notifications_enabled, email_notifications_enabled, created_at, updated_at)
                VALUES (?, 1, 1, ?, ?)
                """, userId, now, now);
    }

    public boolean notificationsEnabled(String userId) {
        List<Boolean> values = jdbc.query(
                "SELECT notifications_enabled FROM user_profile WHERE user_id = ?",
                (rs, rowNum) -> rs.getInt(1) != 0, userId);
        return values.isEmpty() || values.get(0);
    }

    // ==================== 邮箱相关 ====================

    /** 获取用户绑定的邮箱地址，未绑定返回 null。 */
    public String getEmail(String userId) {
        List<String> values = jdbc.query(
                "SELECT email FROM user_profile WHERE user_id = ? AND email IS NOT NULL AND email != ''",
                (rs, rowNum) -> rs.getString(1), userId);
        return values.stream().findFirst().orElse(null);
    }

    /** 设置用户邮箱地址。传 null 或空字符串表示清除绑定。 */
    public void setEmail(String userId, String email) {
        ensureProfile(userId);
        String normalized = (email == null || email.isBlank()) ? null : email.trim();
        jdbc.update("UPDATE user_profile SET email = ?, updated_at = ? WHERE user_id = ?",
                normalized, System.currentTimeMillis(), userId);
    }

    /** 用户是否开启了邮件通知（默认开启）。 */
    public boolean emailNotificationsEnabled(String userId) {
        List<Boolean> values = jdbc.query(
                "SELECT email_notifications_enabled FROM user_profile WHERE user_id = ?",
                (rs, rowNum) -> rs.getInt(1) != 0, userId);
        return values.isEmpty() || values.get(0);
    }

    /** 开启或关闭邮件通知。 */
    public void setEmailNotificationsEnabled(String userId, boolean enabled) {
        ensureProfile(userId);
        jdbc.update("UPDATE user_profile SET email_notifications_enabled = ?, updated_at = ? WHERE user_id = ?",
                enabled ? 1 : 0, System.currentTimeMillis(), userId);
    }
}
