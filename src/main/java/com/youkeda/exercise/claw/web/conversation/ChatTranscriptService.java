package com.youkeda.exercise.claw.web.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.GeneratedArtifact;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ChatTranscriptService {

    private static final TypeReference<List<GeneratedArtifact>> ARTIFACT_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ToolTraceItem>> TOOL_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ArtifactService artifactService;

    public ChatTranscriptService(JdbcTemplate jdbc, ObjectMapper json, ArtifactService artifactService) {
        this.jdbc = jdbc;
        this.json = json;
        this.artifactService = artifactService;
    }

    @Transactional
    public RunStart start(String userId, String conversationId, String message,
                          List<String> attachmentIds, String runId) {
        long now = System.currentTimeMillis();
        String userMessageId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        List<GeneratedArtifact> attachments = resolveAttachments(userId, attachmentIds);
        String visibleMessage = message == null || message.isBlank() ? "请处理这些附件" : message.trim();
        jdbc.update("""
            INSERT INTO chat_messages
            (id, user_id, conversation_id, role, content, attachments_json, status, created_at, updated_at)
            VALUES (?, ?, ?, 'user', ?, ?, 'COMPLETED', ?, ?)
        """, userMessageId, userId, conversationId, visibleMessage, write(attachments), now, now);
        jdbc.update("""
            INSERT INTO chat_messages
            (id, user_id, conversation_id, role, content, status, run_id, created_at, updated_at)
            VALUES (?, ?, ?, 'assistant', '', 'STREAMING', ?, ?, ?)
        """, assistantMessageId, userId, conversationId, runId, now + 1, now + 1);
        jdbc.update("""
            INSERT INTO chat_runs
            (id, user_id, conversation_id, user_message_id, assistant_message_id,
             status, started_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'STREAMING', ?, ?)
        """, runId, userId, conversationId, userMessageId, assistantMessageId, now, now);
        index(userMessageId, conversationId, userId, visibleMessage);
        return new RunStart(runId, userMessageId, assistantMessageId);
    }

    @Transactional
    public void checkpoint(String userId, String runId, String draft,
                           List<ToolTraceItem> tools, List<String> skills) {
        long now = System.currentTimeMillis();
        String toolJson = write(tools);
        String skillJson = write(skills);
        jdbc.update("""
            UPDATE chat_runs SET draft_content = ?, tool_trace_json = ?, skills_json = ?, updated_at = ?
            WHERE id = ? AND user_id = ? AND status = 'STREAMING'
        """, safe(draft), toolJson, skillJson, now, runId, userId);
        jdbc.update("""
            UPDATE chat_messages SET content = ?, tool_trace_json = ?, skills_json = ?, updated_at = ?
            WHERE run_id = ? AND user_id = ? AND role = 'assistant'
        """, safe(draft), toolJson, skillJson, now, runId, userId);
        reindexAssistant(userId, runId, draft);
    }

    @Transactional
    public void complete(String userId, String runId, String reply,
                         List<ToolTraceItem> tools, List<String> skills,
                         List<GeneratedArtifact> artifacts, long totalMs) {
        long now = System.currentTimeMillis();
        String content = safe(reply);
        String toolJson = write(tools);
        String skillJson = write(skills);
        String artifactJson = write(artifacts);
        jdbc.update("""
            UPDATE chat_runs SET status = 'COMPLETED', draft_content = ?, tool_trace_json = ?,
                skills_json = ?, artifacts_json = ?, updated_at = ?, finished_at = ?
            WHERE id = ? AND user_id = ?
        """, content, toolJson, skillJson, artifactJson, now, now, runId, userId);
        jdbc.update("""
            UPDATE chat_messages SET status = 'COMPLETED', content = ?, tool_trace_json = ?,
                skills_json = ?, artifacts_json = ?, total_ms = ?, updated_at = ?
            WHERE run_id = ? AND user_id = ? AND role = 'assistant'
        """, content, toolJson, skillJson, artifactJson, totalMs, now, runId, userId);
        reindexAssistant(userId, runId, content);
    }

    @Transactional
    public void fail(String userId, String runId, String draft,
                     List<ToolTraceItem> tools, List<String> skills, String error, long totalMs) {
        long now = System.currentTimeMillis();
        String message = error == null || error.isBlank() ? "处理失败，请稍后再试" : error;
        jdbc.update("""
            UPDATE chat_runs SET status = 'FAILED', draft_content = ?, tool_trace_json = ?,
                skills_json = ?, error_text = ?, updated_at = ?, finished_at = ?
            WHERE id = ? AND user_id = ?
        """, safe(draft), write(tools), write(skills), message, now, now, runId, userId);
        jdbc.update("""
            UPDATE chat_messages SET status = 'FAILED', content = ?, tool_trace_json = ?,
                skills_json = ?, error_text = ?, total_ms = ?, updated_at = ?
            WHERE run_id = ? AND user_id = ? AND role = 'assistant'
        """, safe(draft), write(tools), write(skills), message, totalMs, now, runId, userId);
        reindexAssistant(userId, runId, draft);
    }

    public TranscriptMessage findByRun(String userId, String runId) {
        List<TranscriptMessage> rows = jdbc.query("""
            SELECT * FROM chat_messages
            WHERE user_id = ? AND run_id = ? AND role = 'assistant' LIMIT 1
        """, (rs, rowNum) -> map(rs), userId, runId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "生成记录不存在");
        return rows.get(0);
    }

    public MessagePage page(String userId, String conversationId, String before, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Cursor cursor = decodeCursor(before);
        List<TranscriptMessage> rows;
        if (cursor == null) {
            rows = jdbc.query("""
                SELECT * FROM chat_messages WHERE user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
            """, (rs, rowNum) -> map(rs), userId, conversationId, safeLimit + 1);
        } else {
            rows = jdbc.query("""
                SELECT * FROM chat_messages WHERE user_id = ? AND conversation_id = ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC LIMIT ?
            """, (rs, rowNum) -> map(rs), userId, conversationId,
                    cursor.createdAt(), cursor.createdAt(), cursor.id(), safeLimit + 1);
        }
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, safeLimit));
        String next = hasMore && !rows.isEmpty()
                ? encodeCursor(rows.get(rows.size() - 1).createdAt(), rows.get(rows.size() - 1).id())
                : null;
        Collections.reverse(rows);
        return new MessagePage(List.copyOf(rows), next);
    }

    public List<TranscriptMessage> all(String userId, String conversationId) {
        return jdbc.query("""
            SELECT * FROM chat_messages WHERE user_id = ? AND conversation_id = ?
            ORDER BY created_at ASC, id ASC LIMIT 5000
        """, (rs, rowNum) -> map(rs), userId, conversationId);
    }

    @Transactional
    public int importMessages(String userId, String conversationId, List<ImportMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        if (messages.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多导入 1000 条消息");
        }
        long base = System.currentTimeMillis();
        int imported = 0;
        for (ImportMessage message : messages) {
            if (message == null || !("user".equals(message.role()) || "assistant".equals(message.role()))) continue;
            String content = safe(message.content());
            if (content.length() > 100_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单条消息不能超过 100000 字符");
            }
            String id = UUID.randomUUID().toString();
            long createdAt = base + imported;
            jdbc.update("""
                INSERT INTO chat_messages
                (id, user_id, conversation_id, role, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
            """, id, userId, conversationId, message.role(), content, createdAt, createdAt);
            index(id, conversationId, userId, content);
            imported++;
        }
        return imported;
    }

    private TranscriptMessage map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Number totalMs = (Number) rs.getObject("total_ms");
        return new TranscriptMessage(
                rs.getString("id"), rs.getString("role"), rs.getString("content"),
                read(rs.getString("attachments_json"), ARTIFACT_LIST),
                read(rs.getString("artifacts_json"), ARTIFACT_LIST),
                read(rs.getString("tool_trace_json"), TOOL_LIST),
                read(rs.getString("skills_json"), STRING_LIST),
                rs.getString("status"), rs.getString("run_id"), rs.getString("error_text"),
                totalMs == null ? null : totalMs.longValue(),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private List<GeneratedArtifact> resolveAttachments(String userId, List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().distinct().limit(8)
                .map(id -> artifactService.load(userId, id)
                        .orElseThrow(() -> new IllegalArgumentException("附件不存在或无权访问: " + id))
                        .metadata())
                .toList();
    }

    private void reindexAssistant(String userId, String runId, String content) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM chat_messages WHERE user_id = ? AND run_id = ? AND role = 'assistant'",
                String.class, userId, runId);
        if (ids.isEmpty()) return;
        jdbc.update("DELETE FROM chat_message_search WHERE message_id = ?", ids.get(0));
        List<String> conversationIds = jdbc.queryForList(
                "SELECT conversation_id FROM chat_messages WHERE id = ?", String.class, ids.get(0));
        if (!conversationIds.isEmpty()) index(ids.get(0), conversationIds.get(0), userId, safe(content));
    }

    private void index(String messageId, String conversationId, String userId, String content) {
        jdbc.update("""
            INSERT INTO chat_message_search(message_id, conversation_id, user_id, content)
            VALUES (?, ?, ?, ?)
        """, messageId, conversationId, userId, safe(content));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception e) { throw new IllegalStateException("序列化聊天记录失败", e); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value == null || value.isBlank() ? "[]" : value, type); }
        catch (Exception e) { throw new IllegalStateException("读取聊天记录失败", e); }
    }

    private static String encodeCursor(long createdAt, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            return new Cursor(Long.parseLong(decoded.substring(0, separator)), decoded.substring(separator + 1));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息游标无效");
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public record RunStart(String runId, String userMessageId, String assistantMessageId) {}
    public record ImportMessage(String role, String content) {}
    private record Cursor(long createdAt, String id) {}
}
