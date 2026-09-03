package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.memory.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.youkeda.exercise.claw.identity.UserExecutionContext;

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
    private final UserExecutionContext executionContext;

    public SqliteTravelPlanStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                         StorageProperties properties) {
        this(jdbc, objectMapper, properties, null);
    }

    @Autowired
    public SqliteTravelPlanStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                      StorageProperties properties,
                                      UserExecutionContext executionContext) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executionContext = executionContext;
    }

    @Override
    public TravelPlanDraft get(String userId) {
        String conversationId = conversationId();
        String sql = conversationId == null
                ? "SELECT plan_json FROM travel_plans WHERE user_id = ?"
                : "SELECT plan_json FROM conversation_travel_plans "
                    + "WHERE user_id = ? AND conversation_id = ?";
        try {
            String json = conversationId == null
                    ? jdbc.queryForObject(sql, String.class, userId)
                    : jdbc.queryForObject(sql, String.class, userId, conversationId);
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

            String conversationId = conversationId();
            if (conversationId == null) {
                jdbc.update("""
                    INSERT OR REPLACE INTO travel_plans (user_id, plan_json, updated_at)
                    VALUES (?, ?, ?)
                """, userId, json, now);
            } else {
                jdbc.update("""
                    INSERT OR REPLACE INTO conversation_travel_plans
                    (user_id, conversation_id, plan_json, updated_at) VALUES (?, ?, ?, ?)
                """, userId, conversationId, json, now);
            }

        } catch (Exception e) {
            log.error("保存旅游方案状态失败 | user={} | error={}", userId, e.getMessage());
        }
    }

    @Override
    public void clear(String userId) {
        String conversationId = conversationId();
        if (conversationId == null) jdbc.update("DELETE FROM travel_plans WHERE user_id = ?", userId);
        else jdbc.update("""
            DELETE FROM conversation_travel_plans WHERE user_id = ? AND conversation_id = ?
        """, userId, conversationId);
    }

    private String conversationId() {
        return executionContext == null ? null : executionContext.currentConversationIdOrNull();
    }
}
