package com.youkeda.exercise.claw.identity;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Channel-neutral user preferences and school binding. */
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
                    user_id               TEXT PRIMARY KEY,
                    school_id             INTEGER,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    created_at            INTEGER NOT NULL,
                    updated_at            INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES app_user(id)
                )
                """);
    }

    public void ensureProfile(String userId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT OR IGNORE INTO user_profile
                    (user_id, notifications_enabled, created_at, updated_at)
                VALUES (?, 1, ?, ?)
                """, userId, now, now);
    }

    public Long getSchoolId(String userId) {
        List<Long> values = jdbc.query(
                "SELECT school_id FROM user_profile WHERE user_id = ? AND school_id IS NOT NULL",
                (rs, rowNum) -> rs.getLong(1), userId);
        return values.stream().findFirst().orElse(null);
    }

    public void setSchoolId(String userId, Long schoolId) {
        ensureProfile(userId);
        jdbc.update("UPDATE user_profile SET school_id = ?, updated_at = ? WHERE user_id = ?",
                schoolId, System.currentTimeMillis(), userId);
    }

    public boolean notificationsEnabled(String userId) {
        List<Boolean> values = jdbc.query(
                "SELECT notifications_enabled FROM user_profile WHERE user_id = ?",
                (rs, rowNum) -> rs.getInt(1) != 0, userId);
        return values.isEmpty() || values.get(0);
    }
}
