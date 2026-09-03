package com.youkeda.exercise.claw.feature.anime.store;

import com.youkeda.exercise.claw.domain.anime.AnimeEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class AnimeScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(AnimeScheduleStore.class);

    private final JdbcTemplate jdbc;

    public AnimeScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新集，已存在则忽略。返回 true 表示新记录 */
    public boolean insertOrIgnoreEpisode(int anilistId, int episode, long airingAt) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update("""
            INSERT OR IGNORE INTO anime_schedule
            (anilist_id, episode, airing_at, notified, created_at)
            VALUES (?, ?, ?, 0, ?)
            """, anilistId, episode, airingAt, now);
        return updated > 0;
    }

    /** 获取未来时段内未通知的播出 */
    @SuppressWarnings("unused")
    public List<AnimeEpisode> getUpcomingEpisodes(long from, long to) {
        return jdbc.query("""
            SELECT s.*, a.title FROM anime_schedule s
            JOIN anime_subscription a ON s.anilist_id = a.anilist_id
            WHERE s.airing_at BETWEEN ? AND ? AND s.notified = 0
            ORDER BY s.airing_at ASC
            """, new AnimeEpisodeRowMapper(), from, to);
    }

    /** 创建提醒任务 */
    public void createReminderTask(int anilistId, int episode, long remindTime, long airingAt) {
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            INSERT OR IGNORE INTO anime_reminder_task
            (anilist_id, episode, remind_time, airing_at, status, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """, anilistId, episode, remindTime, airingAt, now);
    }

    /** 获取所有待执行的提醒任务 */
    public List<ReminderTask> getPendingReminders(long now) {
        return jdbc.query("""
            SELECT r.*, s.airing_at AS airing_at
            FROM anime_reminder_task r
            LEFT JOIN anime_schedule s ON r.anilist_id = s.anilist_id AND r.episode = s.episode
            WHERE r.status = 'PENDING' AND r.remind_time <= ?
            ORDER BY r.remind_time ASC
            """, new ReminderTaskRowMapper(), now);
    }

    /** 标记提醒已发送 */
    public void markReminderSent(long taskId) {
        jdbc.update("UPDATE anime_reminder_task SET status = 'SENT' WHERE id = ?", taskId);
    }

    /** 标记某集已通知 */
    public void markEpisodeNotified(int anilistId, int episode) {
        jdbc.update("UPDATE anime_schedule SET notified = 1 WHERE anilist_id = ? AND episode = ?",
            anilistId, episode);
    }

    /** 最近更新的集（用于"第 X 集已更新"通知） */
    @SuppressWarnings("unused")
    public List<AnimeEpisode> getRecentlyAired(long since) {
        return jdbc.query("""
            SELECT s.*, a.title FROM anime_schedule s
            JOIN anime_subscription a ON s.anilist_id = a.anilist_id
            WHERE s.airing_at BETWEEN ? AND ? AND s.notified = 0
            ORDER BY s.airing_at DESC
            """, new AnimeEpisodeRowMapper(), since, System.currentTimeMillis() / 1000);
    }

    // ---- 内部模型 ----

    public static class ReminderTask {
        private long id;
        private int anilistId;
        private int episode;
        private long remindTime;
        private long airingAt;
        private String status;
        private long createdAt;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }

        public int getAnilistId() { return anilistId; }
        public void setAnilistId(int anilistId) { this.anilistId = anilistId; }

        public int getEpisode() { return episode; }
        public void setEpisode(int episode) { this.episode = episode; }

        public long getRemindTime() { return remindTime; }
        public void setRemindTime(long remindTime) { this.remindTime = remindTime; }

        public long getAiringAt() { return airingAt; }
        public void setAiringAt(long airingAt) { this.airingAt = airingAt; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    }

    private static class AnimeEpisodeRowMapper implements RowMapper<AnimeEpisode> {
        @Override
        public AnimeEpisode mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnimeEpisode ep = new AnimeEpisode();
            ep.setAnilistId(rs.getInt("anilist_id"));
            ep.setEpisode(rs.getInt("episode"));
            ep.setAiringAt(rs.getLong("airing_at"));
            return ep;
        }
    }

    private static class ReminderTaskRowMapper implements RowMapper<ReminderTask> {
        @Override
        public ReminderTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            ReminderTask task = new ReminderTask();
            task.setId(rs.getLong("id"));
            task.setAnilistId(rs.getInt("anilist_id"));
            task.setEpisode(rs.getInt("episode"));
            task.setRemindTime(rs.getLong("remind_time"));
            task.setAiringAt(rs.getLong("airing_at"));
            task.setStatus(rs.getString("status"));
            task.setCreatedAt(rs.getLong("created_at"));
            return task;
        }
    }
}
