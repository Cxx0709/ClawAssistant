package com.youkeda.exercise.claw.feature.goal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** SQLite 成长目标仓库；所有读写均按 user_id 隔离。 */
@Repository
public class GrowthGoalRepository {

    private final JdbcTemplate jdbc;

    public GrowthGoalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS growth_goal (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id          TEXT NOT NULL,
                    title            TEXT NOT NULL,
                    success_criteria TEXT NOT NULL DEFAULT '',
                    deadline         TEXT,
                    status           TEXT NOT NULL DEFAULT 'ACTIVE',
                    progress         INTEGER NOT NULL DEFAULT 0,
                    latest_evidence  TEXT NOT NULL DEFAULT '',
                    created_at       INTEGER NOT NULL,
                    updated_at       INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_growth_goal_user_status
                ON growth_goal(user_id, status, updated_at DESC)
                """);
    }

    public GrowthGoal create(String userId, String title, String successCriteria, String deadline) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO growth_goal
                    (user_id, title, success_criteria, deadline, status, progress,
                     latest_evidence, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, '', ?, ?)
                """, userId, title, successCriteria, deadline, now, now);

        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        if (id == null) {
            throw new IllegalStateException("成长目标创建后未返回ID");
        }
        return findById(userId, id);
    }

    public GrowthGoal findById(String userId, long id) {
        List<GrowthGoal> results = jdbc.query("""
                SELECT id, user_id, title, success_criteria, deadline, status,
                       progress, latest_evidence, created_at, updated_at
                FROM growth_goal
                WHERE id = ? AND user_id = ?
                """, this::mapRow, id, userId);
        return results.isEmpty() ? null : results.get(0);
    }

    public List<GrowthGoal> findByUser(String userId, GrowthGoal.Status status) {
        if (status == null) {
            return jdbc.query("""
                    SELECT id, user_id, title, success_criteria, deadline, status,
                           progress, latest_evidence, created_at, updated_at
                    FROM growth_goal
                    WHERE user_id = ?
                    ORDER BY updated_at DESC, id DESC
                    """, this::mapRow, userId);
        }
        return jdbc.query("""
                SELECT id, user_id, title, success_criteria, deadline, status,
                       progress, latest_evidence, created_at, updated_at
                FROM growth_goal
                WHERE user_id = ? AND status = ?
                ORDER BY updated_at DESC, id DESC
                """, this::mapRow, userId, status.name());
    }

    /** 查找所有 ACTIVE 目标（单用户 Scout 画像场景，无需指定 userId）。 */
    public List<GrowthGoal> findAllActive() {
        return jdbc.query("""
                SELECT id, user_id, title, success_criteria, deadline, status,
                       progress, latest_evidence, created_at, updated_at
                FROM growth_goal
                WHERE status = 'ACTIVE'
                ORDER BY updated_at DESC, id DESC
                """, this::mapRow);
    }

    public GrowthGoal update(String userId, long goalId, String title, String successCriteria,
                               String deadline, Integer progress, String latestEvidence) {
        long now = System.currentTimeMillis();
        int rows = jdbc.update("""
                UPDATE growth_goal SET
                    title = COALESCE(?, title),
                    success_criteria = COALESCE(?, success_criteria),
                    deadline = COALESCE(?, deadline),
                    progress = COALESCE(?, progress),
                    latest_evidence = COALESCE(?, latest_evidence),
                    updated_at = ?
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
                """, title, successCriteria, deadline, progress, latestEvidence, now, goalId, userId);
        return rows > 0 ? findById(userId, goalId) : null;
    }

    public boolean complete(String userId, long goalId) {
        long now = System.currentTimeMillis();
        return jdbc.update("""
                UPDATE growth_goal SET status = 'COMPLETED', progress = 100, updated_at = ?
                WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
                """, now, goalId, userId) > 0;
    }

    public boolean cancel(String userId, long goalId) {
        long now = System.currentTimeMillis();
        return jdbc.update("""
                UPDATE growth_goal SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND user_id = ? AND status IN ('ACTIVE', 'COMPLETED')
                """, now, goalId, userId) > 0;
    }

    private GrowthGoal mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new GrowthGoal(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getString("success_criteria"),
                rs.getString("deadline"),
                GrowthGoal.Status.valueOf(rs.getString("status")),
                rs.getInt("progress"),
                rs.getString("latest_evidence"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }
}
