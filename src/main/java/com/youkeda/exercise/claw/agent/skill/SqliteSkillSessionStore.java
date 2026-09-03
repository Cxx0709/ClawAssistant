package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SqliteSkillSessionStore implements SkillSessionStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSkillSessionStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UserExecutionContext executionContext;
    /** 按 userId 分片锁：保证同一用户 find→modify→save 序列原子性，不同用户互不阻塞 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public SqliteSkillSessionStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public SqliteSkillSessionStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                   UserExecutionContext executionContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.executionContext = executionContext;
        ensureTable();
    }

    private Object lockFor(String userId) {
        return locks.computeIfAbsent(userId, k -> new Object());
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS skill_sessions (
                user_id TEXT PRIMARY KEY,
                active_skill TEXT NOT NULL,
                previous_skill TEXT,
                context_json TEXT NOT NULL DEFAULT '{}',
                activated_at INTEGER NOT NULL,
                last_activity_at INTEGER NOT NULL,
                inactivity_count INTEGER NOT NULL DEFAULT 0
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_skill_sessions (
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                active_skill TEXT NOT NULL,
                previous_skill TEXT,
                context_json TEXT NOT NULL DEFAULT '{}',
                activated_at INTEGER NOT NULL,
                last_activity_at INTEGER NOT NULL,
                inactivity_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (user_id, conversation_id)
            )
        """);
    }

    @Override
    public Optional<SkillSession> find(String userId) {
        String conversationId = conversationId();
        synchronized (lockFor(lockKey(userId, conversationId))) {
            if (conversationId != null) {
                List<SkillSession> results = jdbcTemplate.query(
                        "SELECT user_id, active_skill, previous_skill, context_json, " +
                                "activated_at, last_activity_at, inactivity_count " +
                                "FROM conversation_skill_sessions " +
                                "WHERE user_id = ? AND conversation_id = ?",
                        this::mapRow, userId, conversationId);
                return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
            }
            List<SkillSession> results = jdbcTemplate.query(
                "SELECT user_id, active_skill, previous_skill, context_json, " +
                "activated_at, last_activity_at, inactivity_count " +
                "FROM skill_sessions WHERE user_id = ?",
                this::mapRow,
                userId
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        }
    }

    @Override
    public void save(String userId, SkillSession session) {
        String conversationId = conversationId();
        synchronized (lockFor(lockKey(userId, conversationId))) {
            try {
                String contextJson = objectMapper.writeValueAsString(session.context());
                if (conversationId != null) {
                    jdbcTemplate.update(
                            "INSERT OR REPLACE INTO conversation_skill_sessions " +
                                    "(user_id, conversation_id, active_skill, previous_skill, context_json, " +
                                    "activated_at, last_activity_at, inactivity_count) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            userId, conversationId, session.activeSkill(), session.previousSkill(),
                            contextJson, session.activatedAt().getEpochSecond(),
                            session.lastActivityAt().getEpochSecond(), session.inactivityCount());
                    return;
                }
                jdbcTemplate.update(
                    "INSERT OR REPLACE INTO skill_sessions " +
                    "(user_id, active_skill, previous_skill, context_json, " +
                    "activated_at, last_activity_at, inactivity_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    userId,
                    session.activeSkill(),
                    session.previousSkill(),
                    contextJson,
                    session.activatedAt().getEpochSecond(),
                    session.lastActivityAt().getEpochSecond(),
                    session.inactivityCount()
                );
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize session context for user: {}", userId, e);
            }
        }
    }

    @Override
    public void delete(String userId) {
        String conversationId = conversationId();
        synchronized (lockFor(lockKey(userId, conversationId))) {
            if (conversationId == null) {
                jdbcTemplate.update("DELETE FROM skill_sessions WHERE user_id = ?", userId);
            } else {
                jdbcTemplate.update("""
                    DELETE FROM conversation_skill_sessions
                    WHERE user_id = ? AND conversation_id = ?
                """, userId, conversationId);
            }
        }
    }

    private String conversationId() {
        return executionContext == null ? null : executionContext.currentConversationIdOrNull();
    }

    private static String lockKey(String userId, String conversationId) {
        return conversationId == null ? userId : userId + "\u0000" + conversationId;
    }

    private SkillSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SkillSession(
                rs.getString("user_id"),
                rs.getString("active_skill"),
                rs.getString("previous_skill"),
                Instant.ofEpochSecond(rs.getLong("activated_at")),
                Instant.ofEpochSecond(rs.getLong("last_activity_at")),
                rs.getInt("inactivity_count"),
                parseContext(rs.getString("context_json"))
        );
    }

    private Map<String, String> parseContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(contextJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse skill session context, using empty context", e);
            return Map.of();
        }
    }
}
