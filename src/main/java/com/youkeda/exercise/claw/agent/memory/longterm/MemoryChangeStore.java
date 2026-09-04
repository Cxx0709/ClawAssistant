package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Durable, tenant-scoped change receipts. Snapshots expire after seven days. */
@Component
public class MemoryChangeStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserExecutionContext context;

    public MemoryChangeStore(JdbcTemplate jdbc, ObjectMapper mapper, UserExecutionContext context) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.context = context;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS memory_change (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL,
                    conversation_id TEXT, memory_id TEXT NOT NULL,
                    before_json TEXT, after_json TEXT NOT NULL, created_at INTEGER NOT NULL)
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_change_user ON memory_change(user_id, conversation_id, id)");
    }

    public void record(MemoryItem before, MemoryItem after) {
        expire();
        jdbc.update("""
                INSERT INTO memory_change(user_id, conversation_id, memory_id, before_json, after_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, context.requireUserId(), context.currentConversationIdOrNull(), after.id(),
                before == null ? null : encode(before), encode(after), Instant.now().toEpochMilli());
    }

    public List<Change> recent(String conversationId) {
        expire();
        return jdbc.query("""
                SELECT * FROM memory_change WHERE user_id = ? AND conversation_id = ?
                ORDER BY id DESC LIMIT 10
                """, (rs, n) -> new Change(rs.getLong("id"), decode(rs.getString("before_json")),
                decode(rs.getString("after_json"))), context.requireUserId(), conversationId);
    }

    public Change find(long id) {
        expire();
        return jdbc.query("SELECT * FROM memory_change WHERE user_id = ? AND id = ?",
                (rs, n) -> new Change(rs.getLong("id"), decode(rs.getString("before_json")),
                        decode(rs.getString("after_json"))), context.requireUserId(), id)
                .stream().findFirst().orElse(null);
    }

    public void forget(String memoryId) {
        jdbc.update("DELETE FROM memory_change WHERE user_id = ? AND memory_id = ?", context.requireUserId(), memoryId);
    }

    private void expire() {
        jdbc.update("DELETE FROM memory_change WHERE created_at < ?", Instant.now().minusSeconds(7 * 86400).toEpochMilli());
    }

    private String encode(MemoryItem item) {
        try { return mapper.writeValueAsString(item); }
        catch (Exception e) { throw new IllegalStateException("记忆变更无法保存", e); }
    }

    private MemoryItem decode(String json) {
        if (json == null) return null;
        try { return mapper.readValue(json, MemoryItem.class); }
        catch (Exception e) { throw new IllegalStateException("记忆变更无法读取", e); }
    }

    public record Change(long id, MemoryItem before, MemoryItem after) {}
}
