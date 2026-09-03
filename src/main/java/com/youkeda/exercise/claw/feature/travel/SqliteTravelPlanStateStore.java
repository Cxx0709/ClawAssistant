package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.memory.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 版旅游方案状态存储
 *
 * 数据结构：
 * - 表: travel_plans，主键是 user_id
 * - 使用 INSERT OR REPLACE 实现 upsert
 */
@Component
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class SqliteTravelPlanStateStore implements TravelPlanStateStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteTravelPlanStateStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StorageProperties properties;

    public SqliteTravelPlanStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                         StorageProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public TravelPlanDraft get(String userId) {
        String sql = "SELECT plan_json FROM travel_plans WHERE user_id = ?";
        try {
            String json = jdbc.queryForObject(sql, String.class, userId);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readValue(json, TravelPlanDraft.class);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("EmptyResultDataAccessException")) {
                return null;
            }
            log.warn("读取旅游方案状态失败 | user={} | error={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public void save(String userId, TravelPlanDraft draft) {
        try {
            String json = objectMapper.writeValueAsString(draft);
            long now = System.currentTimeMillis() / 1000;

            // 使用 INSERT OR REPLACE 实现 upsert
            jdbc.update("""
                INSERT OR REPLACE INTO travel_plans (user_id, plan_json, updated_at)
                VALUES (?, ?, ?)
            """, userId, json, now);

        } catch (Exception e) {
            log.error("保存旅游方案状态失败 | user={} | error={}", userId, e.getMessage());
        }
    }

    @Override
    public void clear(String userId) {
        jdbc.update("DELETE FROM travel_plans WHERE user_id = ?", userId);
    }
}
