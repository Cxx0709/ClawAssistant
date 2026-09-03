package com.youkeda.exercise.claw.feature.scout.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 推荐反馈持久化；用于学习用户偏好，调整后续推荐权重。 */
@Repository
public class ScoutFeedbackRepository {

    private final JdbcTemplate jdbc;

    public ScoutFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scout_feedback (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    recommendation_id TEXT NOT NULL,
                    title          TEXT NOT NULL DEFAULT '',
                    topic          TEXT NOT NULL DEFAULT '',
                    rating         TEXT NOT NULL,
                    reason         TEXT NOT NULL DEFAULT '',
                    created_at     INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scout_feedback_topic
                ON scout_feedback(topic, rating, created_at DESC)
                """);
    }

    public void save(String recommendationId, String title, String topic,
                     String rating, String reason) {
        jdbc.update("""
                INSERT INTO scout_feedback
                    (recommendation_id, title, topic, rating, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, recommendationId, title, topic, rating, reason,
                System.currentTimeMillis());
    }

    /** 获取指定主题的负面反馈关键词（用于降低匹配权重）。 */
    public List<String> findNegativeTopics() {
        return jdbc.query("""
                SELECT topic FROM scout_feedback
                WHERE rating = 'NOT_USEFUL' AND topic != ''
                GROUP BY topic
                ORDER BY MAX(created_at) DESC
                LIMIT 20
                """, (rs, rowNum) -> rs.getString("topic"));
    }

    /** 获取指定主题的正面反馈（用于提升匹配权重）。 */
    public List<String> findPositiveTopics() {
        return jdbc.query("""
                SELECT topic FROM scout_feedback
                WHERE rating = 'USEFUL' AND topic != ''
                GROUP BY topic
                ORDER BY MAX(created_at) DESC
                LIMIT 20
                """, (rs, rowNum) -> rs.getString("topic"));
    }

    public List<FeedbackEntry> findRecent(int limit) {
        return jdbc.query("""
                SELECT id, recommendation_id, title, topic, rating, reason, created_at
                FROM scout_feedback
                ORDER BY created_at DESC
                LIMIT ?
                """, this::mapRow, limit);
    }

    private FeedbackEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FeedbackEntry(
                rs.getLong("id"),
                rs.getString("recommendation_id"),
                rs.getString("title"),
                rs.getString("topic"),
                rs.getString("rating"),
                rs.getString("reason"),
                rs.getLong("created_at"));
    }

    public record FeedbackEntry(
            long id, String recommendationId, String title,
            String topic, String rating, String reason, long createdAt) {}
}
