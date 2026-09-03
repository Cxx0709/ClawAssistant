package com.youkeda.exercise.claw.feature.campus.store;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class CampusNotificationStore {

    private static final Logger log = LoggerFactory.getLogger(CampusNotificationStore.class);

    private final JdbcTemplate jdbc;

    public CampusNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 与已有记录去重（按 url + source 联合判断），返回全新的通知列表并插入DB
     */
    public List<NotificationItem> deduplicate(List<NotificationItem> fetched) {
        List<NotificationItem> newItems = new ArrayList<>();
        for (NotificationItem item : fetched) {
            try {
                // 先检查 (url, source) 是否已存在
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM campus_notice WHERE url = ? AND source = ?",
                    Integer.class, item.getUrl(), item.getSource());
                if (count != null && count > 0) {
                    continue; // 已存在，跳过
                }

                jdbc.update("""
                    INSERT INTO campus_notice (title, url, publish_at, source, status)
                    VALUES (?, ?, ?, ?, 'UNPROCESSED')
                    """, item.getTitle(), item.getUrl(), item.getPublishAt(), item.getSource());
                newItems.add(item);
            } catch (Exception e) {
                log.warn("去重写入失败 | url={} | source={}", item.getUrl(), item.getSource(), e);
            }
        }
        if (!newItems.isEmpty()) {
            log.info("发现新通知 | source={} | count={}",
                newItems.get(0).getSource(), newItems.size());
        }
        return newItems;
    }

    public void update(NotificationItem item) {
        jdbc.update("""
            UPDATE campus_notice SET type=?, confidence=?, score_source=?,
                classifier_reason=?, status=?, processed_at=?
            WHERE url=? AND source=?
            """, item.getType(), item.getConfidence(), item.getScoreSource(),
            item.getClassifierReason(), "CLASSIFIED",
            System.currentTimeMillis() / 1000, item.getUrl(), item.getSource());
    }

    public void updateContent(Long id, String content) {
        jdbc.update("UPDATE campus_notice SET content = ? WHERE id = ?", content, id);
    }

    public List<NotificationItem> getUnprocessed() {
        return jdbc.query(
            "SELECT * FROM campus_notice WHERE status = 'UNPROCESSED' ORDER BY created_at ASC",
            new NotificationItemRowMapper());
    }

    private static class NotificationItemRowMapper implements RowMapper<NotificationItem> {
        @Override
        public NotificationItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            NotificationItem item = new NotificationItem();
            item.setId(rs.getLong("id"));
            item.setTitle(rs.getString("title"));
            item.setUrl(rs.getString("url"));
            item.setPublishAt(rs.getString("publish_at"));
            item.setContent(rs.getString("content"));
            item.setSource(rs.getString("source"));
            item.setStatus(rs.getString("status"));
            String type = rs.getString("type");
            if (type != null) item.setType(type);
            item.setConfidence(rs.getDouble("confidence"));
            item.setScoreSource(rs.getString("score_source"));
            item.setClassifierReason(rs.getString("classifier_reason"));
            long processedAt = rs.getLong("processed_at");
            if (!rs.wasNull()) item.setProcessedAt(processedAt);
            long createdAt = rs.getLong("created_at");
            if (!rs.wasNull()) item.setCreatedAt(createdAt);
            return item;
        }
    }
}
