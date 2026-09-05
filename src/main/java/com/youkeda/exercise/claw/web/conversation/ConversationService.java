package com.youkeda.exercise.claw.web.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class ConversationService {

    private static final int MAX_PREVIEW_LENGTH = 120;

    private final JdbcTemplate jdbc;

    public ConversationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Conversation> list(String userId, boolean archived, int limit) {
        return page(userId, archived, false, null, null, limit).items();
    }

    public ConversationPage page(String userId, boolean archived, boolean deleted,
                                 String query, String cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        PageCursor decoded = decodeCursor(cursor);
        String normalizedQuery = clean(query);
        StringBuilder sql = new StringBuilder("""
            SELECT id, title, pinned, archived, last_message_preview, created_at,
                   COALESCE(last_message_at, updated_at) AS activity_at, deleted_at, role_id
            FROM conversations
            WHERE user_id = ? AND archived = ?
        """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.add(archived ? 1 : 0);
        sql.append(deleted ? " AND deleted_at IS NOT NULL" : " AND deleted_at IS NULL");
        if (!normalizedQuery.isBlank()) {
            sql.append("""
                 AND (title LIKE ? ESCAPE '\\' OR id IN (
                    SELECT conversation_id FROM chat_message_search
                    WHERE user_id = ? AND chat_message_search MATCH ?
                 ))
            """);
            args.add("%" + escapeLike(normalizedQuery) + "%");
            args.add(userId);
            args.add(ftsQuery(normalizedQuery));
        }
        if (decoded != null) {
            sql.append("""
                 AND (pinned < ? OR (pinned = ? AND COALESCE(last_message_at, updated_at) < ?)
                      OR (pinned = ? AND COALESCE(last_message_at, updated_at) = ? AND id < ?))
            """);
            args.add(decoded.pinned());
            args.add(decoded.pinned());
            args.add(decoded.activityAt());
            args.add(decoded.pinned());
            args.add(decoded.activityAt());
            args.add(decoded.id());
        }
        sql.append(" ORDER BY pinned DESC, activity_at DESC, id DESC LIMIT ?");
        args.add(safeLimit + 1);
        List<Conversation> rows = jdbc.query(sql.toString(), (rs, rowNum) -> map(rs), args.toArray());
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = new java.util.ArrayList<>(rows.subList(0, safeLimit));
        Conversation last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        String next = hasMore ? encodeCursor(last.pinned(), last.updatedAt(), last.id()) : null;
        return new ConversationPage(List.copyOf(rows), next);
    }

    public Conversation create(String userId) {
        return create(userId, null);
    }

    public Conversation create(String userId, String roleId) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            INSERT INTO conversations
            (id, user_id, title, title_source, last_message_preview, created_at, updated_at,
             last_message_at, metadata_updated_at, role_id)
            VALUES (?, ?, '新对话', 'AUTO', '', ?, ?, ?, ?, ?)
        """, id, userId, now, now, now, now, roleId);
        return requireOwned(userId, id);
    }

    public Conversation updateRole(String userId, String id, String roleId) {
        requireOwned(userId, id);
        jdbc.update("UPDATE conversations SET role_id = ?, updated_at = ? WHERE user_id = ? AND id = ?",
                roleId, System.currentTimeMillis() / 1000, userId, id);
        return requireOwned(userId, id);
    }

    public Conversation ensureDefault(String userId) {
        List<Conversation> conversations = list(userId, false, 1);
        return conversations.isEmpty() ? create(userId) : conversations.get(0);
    }

    public Conversation requireOwned(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空");
        }
        List<Conversation> matches = jdbc.query("""
            SELECT id, title, pinned, archived, last_message_preview, created_at,
                   COALESCE(last_message_at, updated_at) AS activity_at, deleted_at, role_id
            FROM conversations WHERE user_id = ? AND id = ?
        """, (rs, rowNum) -> map(rs), userId, conversationId);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在");
        }
        return matches.get(0);
    }

    public Conversation update(String userId, String id, String title, Boolean pinned,
                               Boolean archived, Boolean deleted) {
        Conversation current = requireOwned(userId, id);
        String nextTitle = title == null ? current.title() : normalizeTitle(title);
        boolean nextPinned = pinned == null ? current.pinned() : pinned;
        boolean nextArchived = archived == null ? current.archived() : archived;
        Long nextDeletedAt = current.deletedAt();
        if (Boolean.TRUE.equals(deleted)) nextDeletedAt = System.currentTimeMillis() / 1000;
        if (Boolean.FALSE.equals(deleted)) nextDeletedAt = null;
        if (nextArchived || nextDeletedAt != null) nextPinned = false;
        jdbc.update("""
            UPDATE conversations
            SET title = ?, title_source = CASE WHEN ? IS NULL THEN title_source ELSE 'USER' END,
                pinned = ?, archived = ?, deleted_at = ?, metadata_updated_at = ?
            WHERE user_id = ? AND id = ?
        """, nextTitle, title, nextPinned ? 1 : 0, nextArchived ? 1 : 0,
                nextDeletedAt, System.currentTimeMillis() / 1000, userId, id);
        return requireOwned(userId, id);
    }

    public Conversation update(String userId, String id, String title, Boolean pinned, Boolean archived) {
        return update(userId, id, title, pinned, archived, null);
    }

    public void touchAfterMessage(String userId, String id, String userMessage) {
        requireOwned(userId, id);
        String preview = truncate(clean(userMessage), MAX_PREVIEW_LENGTH);
        String generatedTitle = ConversationTitleGenerator.fromMessage(userMessage);
        jdbc.update("""
            UPDATE conversations
            SET title = CASE WHEN title_source = 'AUTO' AND title = '新对话' THEN ? ELSE title END,
                last_message_preview = ?, updated_at = ?, last_message_at = ?
            WHERE user_id = ? AND id = ?
        """, generatedTitle, preview, System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000, userId, id);
    }

    public void delete(String userId, String id) {
        update(userId, id, null, false, false, true);
    }

    @Transactional
    public void purge(String userId, String id) {
        requireOwned(userId, id);
        jdbc.update("DELETE FROM chat_message_search WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM chat_messages WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM chat_runs WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM context_messages WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM conversation_summaries WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM conversation_agent_plans WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM conversation_travel_plans WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM conversation_skill_sessions WHERE user_id = ? AND conversation_id = ?", userId, id);
        jdbc.update("DELETE FROM conversations WHERE user_id = ? AND id = ?", userId, id);
    }

    private static Conversation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Number deleted = (Number) rs.getObject("deleted_at");
        String roleId = null;
        try { roleId = rs.getString("role_id"); } catch (Exception ignored) {}
        return new Conversation(rs.getString("id"), rs.getString("title"), rs.getBoolean("pinned"),
                rs.getBoolean("archived"), rs.getString("last_message_preview"),
                rs.getLong("created_at"), rs.getLong("activity_at"),
                deleted == null ? null : deleted.longValue(), roleId);
    }

    private static String ftsQuery(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encodeCursor(boolean pinned, long activityAt, String id) {
        String raw = (pinned ? 1 : 0) + ":" + activityAt + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static PageCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            return new PageCursor(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), parts[2]);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话游标无效");
        }
    }

    private record PageCursor(int pinned, long activityAt, String id) {}

    private static String normalizeTitle(String value) {
        String title = truncate(clean(value), 60);
        if (title.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        return title;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
