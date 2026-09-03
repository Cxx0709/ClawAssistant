package com.youkeda.exercise.claw.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.model.PlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.youkeda.exercise.claw.identity.UserExecutionContext;

/**
 * SQLite 版 PlanState 存储（ADR §8/Phase 2）。
 *
 * <p>让多步任务执行状态在重启后可恢复（替代 {@link DefaultPlanStore} 的内存 volatile）。
 * 单用户单行：{@code agent_plans} 表固定 id=1（CHECK 约束保证单行）。
 *
 * <p>与 {@code feature/travel} 的 {@code travel_plans} 表隔离（那是旅游方案草稿，
 * 语义不同），不挤占。
 */
@Component
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class SqlitePlanStore implements PlanStore {

    private static final Logger log = LoggerFactory.getLogger(SqlitePlanStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final UserExecutionContext executionContext;

    public SqlitePlanStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, null);
    }

    @Autowired
    public SqlitePlanStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
                           UserExecutionContext executionContext) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.executionContext = executionContext;
    }

    @Override
    public PlanState get() {
        try {
            String conversationId = conversationId();
            String json = conversationId == null
                    ? jdbc.queryForObject("SELECT plan_json FROM agent_plans WHERE id = 1", String.class)
                    : jdbc.queryForObject("""
                        SELECT plan_json FROM conversation_agent_plans
                        WHERE user_id = ? AND conversation_id = ?
                    """, String.class, executionContext.requireUserId(), conversationId);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readValue(json, PlanState.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("读取 PlanState 失败 | error={}", e.getMessage());
            return null;
        }
    }

    @Override
    public void save(PlanState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            long now = System.currentTimeMillis() / 1000;
            String conversationId = conversationId();
            if (conversationId == null) {
                jdbc.update("""
                    INSERT OR REPLACE INTO agent_plans (id, plan_json, updated_at)
                    VALUES (1, ?, ?)
                """, json, now);
            } else {
                jdbc.update("""
                    INSERT OR REPLACE INTO conversation_agent_plans
                    (user_id, conversation_id, plan_json, updated_at) VALUES (?, ?, ?, ?)
                """, executionContext.requireUserId(), conversationId, json, now);
            }
            log.debug("PlanState 已落库 | version={}", state != null ? state.getVersion() : "null");
        } catch (Exception e) {
            log.error("保存 PlanState 失败 | error={}", e.getMessage());
        }
    }

    @Override
    public void clear() {
        String conversationId = conversationId();
        if (conversationId == null) jdbc.update("DELETE FROM agent_plans WHERE id = 1");
        else jdbc.update("""
            DELETE FROM conversation_agent_plans WHERE user_id = ? AND conversation_id = ?
        """, executionContext.requireUserId(), conversationId);
        log.debug("PlanState 已清除");
    }

    private String conversationId() {
        return executionContext == null ? null : executionContext.currentConversationIdOrNull();
    }
}
