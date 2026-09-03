package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite 会话上下文存储
 *
 * <p>无 userId 重载从当前 Agent 执行作用域解析用户，不允许全局 owner 回退。
 * 外部组件若需指定特定 userId 查询，可调用带 userId 参数的重载方法。
 *
 * <p>数据结构：
 * - 表: context_messages，每行是一条 Message 的 JSON
 * - 使用 INSERT 追加、DELETE 限长、created_at 做 TTL
 */
@Component
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class SqliteContextStore implements ContextStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteContextStore.class);

    /** 启动恢复扫描阈值：RUNNING 超过该时长视为崩溃残留，转 INCOMPLETE。 */
    private static final int STALE_RUN_TIMEOUT_SECONDS = 10 * 60;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StorageProperties props;
    private final UserExecutionContext userExecutionContext;

    /** 一次性回填的完成标记（内存态，进程内只做一次）。 */
    private final Set<String> backfilledUsers = new HashSet<>();
    private final Object backfillLock = new Object();
    /** 启动恢复扫描的完成标记（进程内只做一次）。 */
    private final Set<String> recoveredUsers = new HashSet<>();
    private final Object recoveryLock = new Object();

    public SqliteContextStore(JdbcTemplate jdbc, ObjectMapper mapper,
                               StorageProperties props, UserExecutionContext userExecutionContext) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.userExecutionContext = userExecutionContext;
    }

    // ==================== ContextStore 接口实现（单用户自动兜底） ====================

    @Override
    public List<Message> getHistory(int maxMessages) {
        String conversationId = resolveConversationId();
        return conversationId == null
                ? getHistory(resolveUserId(), maxMessages)
                : getHistory(resolveUserId(), conversationId, maxMessages);
    }

    @Override
    public void append(String role, String content) {
        appendCurrent(new Message(role, content));
    }

    @Override
    public void append(String role, String content,
                        String mediaEncryptParam, String mediaAesKey,
                        String mediaUrl) {
        appendCurrent(new Message(role, content, mediaEncryptParam, mediaAesKey, mediaUrl));
    }

    @Override
    public void append(Message message) {
        appendCurrent(message);
    }

    @Override
    public Message findLastByPrefix(String contentPrefix) {
        String conversationId = resolveConversationId();
        if (conversationId == null) return findLastByPrefix(resolveUserId(), contentPrefix);
        return findLastByPrefix(resolveUserId(), conversationId, contentPrefix);
    }

    @Override
    public List<Message> findAllByPrefix(String contentPrefix) {
        String conversationId = resolveConversationId();
        if (conversationId == null) return findAllByPrefix(resolveUserId(), contentPrefix);
        return findAllByPrefix(resolveUserId(), conversationId, contentPrefix);
    }

    @Override
    public void clear() {
        String conversationId = resolveConversationId();
        if (conversationId == null) clear(resolveUserId());
        else clear(resolveUserId(), conversationId);
    }

    // ==================== 指定 userId 的查询（供 UserBehaviorAnalyzer 等组件直接调用） ====================

    public List<Message> getHistory(String userId, int maxMessages) {
        // 查询最近 maxMessages 条消息（按时间正序，created_at 同值用 id 决胜）。
        // ADR §7.3/1E：不再向前补取 tool_calls——主路径已改用 Turn 切割读取（getTurns），
        // 工具轮次作为原子单位不会在窗口边界被切破，无需此补丁。
        String sql = """
            SELECT message_json FROM context_messages
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """;

        List<String> jsons = jdbc.queryForList(sql, String.class, userId, maxMessages);
        List<Message> result = new ArrayList<>();
        for (int i = jsons.size() - 1; i >= 0; i--) {
            try {
                Message msg = mapper.readValue(jsons.get(i), Message.class);
                if (msg.role() != null && msg.content() != null) {
                    result.add(msg);
                }
            } catch (Exception e) {
                log.warn("反序列化消息失败 | json={}", jsons.get(i));
            }
        }
        return result;
    }

    @Override
    public List<Message> getHistory(String userId, String conversationId, int maxMessages) {
        List<String> jsons = jdbc.queryForList("""
            SELECT message_json FROM context_messages
            WHERE user_id = ? AND conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """, String.class, userId, conversationId, maxMessages);
        return deserializeChronologically(jsons);
    }

    public Message findLastByPrefix(String userId, String contentPrefix) {
        String sql = """
            SELECT message_json FROM context_messages
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
        """;

        List<String> jsons = jdbc.queryForList(sql, String.class, userId);
        for (String json : jsons) {
            try {
                Message msg = mapper.readValue(json, Message.class);
                if (msg.content() != null && msg.content().startsWith(contentPrefix)) {
                    return msg;
                }
            } catch (JsonProcessingException ignored) {}
        }
        return null;
    }

    public List<Message> findAllByPrefix(String userId, String contentPrefix) {
        String sql = """
            SELECT message_json FROM context_messages
            WHERE user_id = ?
            ORDER BY created_at ASC, id ASC
        """;

        List<String> jsons = jdbc.queryForList(sql, String.class, userId);
        List<Message> result = new ArrayList<>();
        for (String json : jsons) {
            try {
                Message msg = mapper.readValue(json, Message.class);
                if (msg.content() != null && msg.content().startsWith(contentPrefix)) {
                    result.add(msg);
                }
            } catch (JsonProcessingException ignored) {}
        }
        return result;
    }

    private Message findLastByPrefix(String userId, String conversationId, String contentPrefix) {
        List<String> jsons = jdbc.queryForList("""
            SELECT message_json FROM context_messages
            WHERE user_id = ? AND conversation_id = ?
            ORDER BY created_at DESC, id DESC
        """, String.class, userId, conversationId);
        for (String json : jsons) {
            Message message = deserialize(json);
            if (message != null && message.content() != null
                    && message.content().startsWith(contentPrefix)) return message;
        }
        return null;
    }

    private List<Message> findAllByPrefix(String userId, String conversationId, String contentPrefix) {
        List<String> jsons = jdbc.queryForList("""
            SELECT message_json FROM context_messages
            WHERE user_id = ? AND conversation_id = ?
            ORDER BY created_at ASC, id ASC
        """, String.class, userId, conversationId);
        List<Message> result = new ArrayList<>();
        for (String json : jsons) {
            Message message = deserialize(json);
            if (message != null && message.content() != null
                    && message.content().startsWith(contentPrefix)) result.add(message);
        }
        return result;
    }

    public void append(String userId, String role, String content) {
        append(userId, role, content, null, null, null);
    }

    public void append(String userId, String role, String content,
                        String mediaEncryptParam, String mediaAesKey,
                        String mediaUrl) {
        append(userId, new Message(role, content, mediaEncryptParam, mediaAesKey, mediaUrl));
    }

    public void append(String userId, Message message) {
        try {
            String json = mapper.writeValueAsString(message);

            // 插入新消息
            jdbc.update("INSERT INTO context_messages (user_id, message_json) VALUES (?, ?)",
                userId, json);

            // 删除超出限制的旧消息（保留最新的 maxMessages 条）
            jdbc.update("""
                DELETE FROM context_messages
                WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM context_messages
                    WHERE user_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                )
            """, userId, userId, props.getMaxMessages());

        } catch (JsonProcessingException e) {
            log.error("序列化消息失败 | userId={}", userId, e);
        }
    }

    public void clear(String userId) {
        jdbc.update("DELETE FROM context_messages WHERE user_id = ?", userId);
        log.debug("已清除用户 SQLite 上下文 | userId={}", userId);
    }

    // ==================== Turn 维度（ADR Phase 1B） ====================

    /**
     * 开启一个新 Turn 并写入其首条（用户）消息。
     *
     * <p>roundId 由调用方生成（UUID），seq 由存储分配（每用户单调递增）。
     * 返回分配的 seq，供记录 {@code ConversationTurn}。
     *
     * <p>旧行（round_id IS NULL）在首次调用前由 {@link #backfillTurnFields(String)} 一次性回填。
     */
    public long beginTurn(String userId, String roundId, TurnInitiator initiator, Message firstMessage) {
        backfillTurnFields(userId);
        long seq = nextSeq(userId);
        jdbc.update("""
            INSERT INTO context_messages (user_id, message_json, round_id, seq, turn_status, turn_initiator)
            VALUES (?, ?, ?, ?, ?, ?)
        """, userId, serialize(firstMessage), roundId, seq,
                TurnStatus.RUNNING.name(), initiator.name());
        return seq;
    }

    /**
     * 向指定 Turn 追加消息（同一轮次内保持 round_id 不变，seq 递增）。
     * 由 Agent Run 写路径调用。
     */
    public void appendToTurn(String userId, String roundId, Message message) {
        long seq = nextSeq(userId);
        jdbc.update("""
            INSERT INTO context_messages (user_id, message_json, round_id, seq, turn_status, turn_initiator)
            VALUES (?, ?, ?, ?, ?, ?)
        """, userId, serialize(message), roundId, seq,
                TurnStatus.RUNNING.name(), TurnInitiator.USER.name());
    }

    /**
     * 关闭 Turn（标记 COMPLETED）。同轮内消息统一更新。
     */
    public void closeTurn(String userId, String roundId) {
        jdbc.update("""
            UPDATE context_messages SET turn_status = ? WHERE user_id = ? AND round_id = ?
        """, TurnStatus.COMPLETED.name(), userId, roundId);
    }

    /**
     * 标记 Turn 为 INCOMPLETE（异常中断）。同轮内消息统一更新。
     */
    public void markTurnIncomplete(String userId, String roundId) {
        jdbc.update("""
            UPDATE context_messages SET turn_status = ? WHERE user_id = ? AND round_id = ?
        """, TurnStatus.INCOMPLETE.name(), userId, roundId);
    }

    @Override
    public List<ConversationTurn> getTurns(int maxTurns) {
        String conversationId = resolveConversationId();
        return conversationId == null
                ? getTurns(resolveUserId(), maxTurns)
                : getTurnsForConversation(resolveUserId(), conversationId, maxTurns);
    }

    @Override
    public long beginTurn(String roundId, TurnInitiator initiator, Message firstMessage) {
        String conversationId = resolveConversationId();
        if (conversationId == null) {
            return beginTurn(resolveUserId(), roundId, initiator, firstMessage);
        }
        long seq = nextSeq(resolveUserId(), conversationId);
        jdbc.update("""
            INSERT INTO context_messages
            (user_id, conversation_id, message_json, round_id, seq, turn_status, turn_initiator)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, resolveUserId(), conversationId, serialize(firstMessage), roundId, seq,
                TurnStatus.RUNNING.name(), initiator.name());
        return seq;
    }

    @Override
    public void appendToTurn(String roundId, Message message) {
        String conversationId = resolveConversationId();
        if (conversationId == null) {
            appendToTurn(resolveUserId(), roundId, message);
            return;
        }
        jdbc.update("""
            INSERT INTO context_messages
            (user_id, conversation_id, message_json, round_id, seq, turn_status, turn_initiator)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, resolveUserId(), conversationId, serialize(message), roundId,
                nextSeq(resolveUserId(), conversationId), TurnStatus.RUNNING.name(),
                TurnInitiator.USER.name());
    }

    @Override
    public void closeTurn(String roundId) {
        String conversationId = resolveConversationId();
        if (conversationId == null) closeTurn(resolveUserId(), roundId);
        else jdbc.update("""
            UPDATE context_messages SET turn_status = ?
            WHERE user_id = ? AND conversation_id = ? AND round_id = ?
        """, TurnStatus.COMPLETED.name(), resolveUserId(), conversationId, roundId);
    }

    @Override
    public void markTurnIncomplete(String roundId) {
        String conversationId = resolveConversationId();
        if (conversationId == null) markTurnIncomplete(resolveUserId(), roundId);
        else jdbc.update("""
            UPDATE context_messages SET turn_status = ?
            WHERE user_id = ? AND conversation_id = ? AND round_id = ?
        """, TurnStatus.INCOMPLETE.name(), resolveUserId(), conversationId, roundId);
    }

    private List<ConversationTurn> getTurnsForConversation(
            String userId, String conversationId, int maxTurns) {
        int rowLimit = Math.max(100, maxTurns * 20);
        List<MessageRow> rows = jdbc.query("""
            SELECT id, message_json, round_id, seq, turn_status, turn_initiator, created_at
            FROM context_messages
            WHERE user_id = ? AND conversation_id = ?
            ORDER BY created_at DESC, id DESC LIMIT ?
        """, (rs, rowNum) -> new MessageRow(
                rs.getLong("id"), rs.getString("message_json"), rs.getString("round_id"),
                nullableLong(rs, "seq"), rs.getString("turn_status"),
                rs.getString("turn_initiator"), rs.getLong("created_at")),
                userId, conversationId, rowLimit);
        Map<String, List<MessageRow>> byRound = new LinkedHashMap<>();
        for (MessageRow row : rows) {
            String key = row.roundId() == null ? "orphan-" + row.id() : row.roundId();
            byRound.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<ConversationTurn> turns = new ArrayList<>();
        for (Map.Entry<String, List<MessageRow>> entry : byRound.entrySet()) {
            List<MessageRow> group = entry.getValue();
            group.sort(Comparator.comparingLong(MessageRow::id));
            String statusValue = group.get(0).turnStatus();
            TurnStatus status = statusValue == null
                    ? TurnStatus.COMPLETED : TurnStatus.valueOf(statusValue);
            if (status == TurnStatus.INCOMPLETE) continue;
            List<Message> messages = group.stream().map(row -> deserialize(row.messageJson()))
                    .filter(message -> message != null && message.role() != null
                            && message.content() != null)
                    .toList();
            if (messages.isEmpty()) continue;
            String initiatorValue = group.get(0).turnInitiator();
            TurnInitiator initiator = initiatorValue == null
                    ? TurnInitiator.USER : TurnInitiator.valueOf(initiatorValue);
            Long seq = group.get(0).seq();
            turns.add(new ConversationTurn(entry.getKey(), seq == null ? group.get(0).id() : seq,
                    status, initiator, messages,
                    Instant.ofEpochSecond(group.get(0).createdAt())));
            if (turns.size() >= maxTurns) break;
        }
        return turns;
    }

    /**
     * 读取最近 maxTurns 个 Turn，按 seq 倒序返回（最新在前）。
     *
     * <p>过滤 INCOMPLETE Turn（不进窗口）。未设置 round_id 的旧行退化为
     * 「每条消息一个 Turn」逐条返回，与 getHistory 单条语义一致。
     */
    public List<ConversationTurn> getTurns(String userId, int maxTurns) {
        backfillTurnFields(userId);
        recoverStaleRuns(userId);
        List<ConversationTurn> result = new ArrayList<>();

        // 先读最近 maxTurns*4 条（保守：一个 Turn 至多几条消息），
        // 分组后若不足 maxTurns 再向更旧方向补。
        int fetch = Math.max(20, maxTurns * 4);
        int skip = 0;
        while (true) {
            List<MessageRow> rows = loadTurnRows(userId, fetch, skip);
            if (rows.isEmpty()) break;

            // 按 round_id 分组（NULL 退化为单条成 Turn）
            Map<String, List<MessageRow>> byRound = new LinkedHashMap<>();
            for (MessageRow row : rows) {
                String key = row.roundId() != null ? row.roundId() : "orphan-" + row.id();
                byRound.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
            // 组内按 id 正序（时间序）
            for (List<MessageRow> group : byRound.values()) {
                group.sort(Comparator.comparingLong(MessageRow::id));
            }

            List<ConversationTurn> turns = new ArrayList<>();
            for (List<MessageRow> group : byRound.values()) {
                List<Message> msgs = new ArrayList<>();
                for (MessageRow row : group) {
                    Message m = deserialize(row.messageJson());
                    if (m != null && m.role() != null && m.content() != null) {
                        msgs.add(m);
                    }
                }
                if (msgs.isEmpty()) continue;
                String roundId = group.get(0).roundId();
                TurnStatus status = group.get(0).turnStatus() != null
                        ? TurnStatus.valueOf(group.get(0).turnStatus())
                        : TurnStatus.COMPLETED;
                TurnInitiator initiator = group.get(0).turnInitiator() != null
                        ? TurnInitiator.valueOf(group.get(0).turnInitiator())
                        : TurnInitiator.USER;
                turns.add(new ConversationTurn(
                        roundId != null ? roundId : "orphan-" + group.get(0).id(),
                        group.get(0).seq() != null ? group.get(0).seq() : group.get(0).id(),
                        status, initiator, msgs,
                        Instant.ofEpochSecond(group.get(0).createdAt())));
            }
            // 按 seq 倒序（最新在前）
            turns.sort((a, b) -> Long.compare(b.seq(), a.seq()));
            result.addAll(turns);

            if (result.size() >= maxTurns || rows.size() < fetch) break;
            skip += fetch;
        }

        // 截断到 maxTurns，过滤 INCOMPLETE
        List<ConversationTurn> filtered = new ArrayList<>();
        for (ConversationTurn turn : result) {
            if (turn.status() == TurnStatus.INCOMPLETE) continue;
            filtered.add(turn);
            if (filtered.size() >= maxTurns) break;
        }
        return filtered;
    }

    // ==================== Turn 内部方法 ====================

    /**
     * 启动恢复扫描（ADR §3.4/§7.1）：RUNNING 超时 → INCOMPLETE。
     *
     * <p>异常/崩溃逃逸的 Turn 会残留 RUNNING 状态（可能含悬空 tool_calls），
     * 若不处理会进入窗口毒化后续请求。此处将超过 {@link #STALE_RUN_TIMEOUT_SECONDS}
     * 的 RUNNING Turn 标记为 INCOMPLETE（不进窗口）。进程内只扫描一次。
     */
    private void recoverStaleRuns(String userId) {
        synchronized (recoveryLock) {
            if (recoveredUsers.contains(userId)) return;
            try {
                long cutoff = System.currentTimeMillis() / 1000 - STALE_RUN_TIMEOUT_SECONDS;
                int updated = jdbc.update("""
                    UPDATE context_messages SET turn_status = ?
                    WHERE user_id = ? AND turn_status = ? AND created_at < ?
                """, TurnStatus.INCOMPLETE.name(), userId, TurnStatus.RUNNING.name(), cutoff);
                if (updated > 0) {
                    log.info("恢复扫描：{} 个超时 RUNNING Turn 标记为 INCOMPLETE | userId={}", updated, userId);
                }
                recoveredUsers.add(userId);
            } catch (Exception e) {
                log.warn("恢复扫描失败 | userId={} | error={}", userId, e.getMessage());
            }
        }
    }

    /** 一次性回填存量行的 Turn 维度（round_id / seq / status / initiator）。 */
    private void backfillTurnFields(String userId) {
        synchronized (backfillLock) {
            if (backfilledUsers.contains(userId)) return;
            try {
                // 当前已用的最大 seq，新分配从其续接，避免覆盖已有（增量迁移场景）
                Long maxSeq = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(seq), 0) FROM context_messages WHERE user_id = ?
                """, Long.class, userId);
                long seq = (maxSeq == null ? 0 : maxSeq);

                List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, created_at FROM context_messages
                    WHERE user_id = ? AND round_id IS NULL
                    ORDER BY created_at ASC, id ASC
                """, userId);
                if (rows.isEmpty()) {
                    backfilledUsers.add(userId);
                    return;
                }

                // 相邻成组：round_id 为 NULL 的行按 (created_at,id) 相邻成组。
                // created_at 相同视为同一轮（同一工具轮次常同秒入库）。
                int group = 0;
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, Object> row = rows.get(i);
                    long id = ((Number) row.get("id")).longValue();
                    long createdAt = ((Number) row.get("created_at")).longValue();

                    boolean newGroup = i == 0
                            || createdAt != ((Number) rows.get(i - 1).get("created_at")).longValue();
                    if (newGroup) {
                        group++;
                        seq++;
                    }
                    String roundId = "backfill-" + userId + "-" + group;
                    jdbc.update("""
                        UPDATE context_messages SET round_id = ?, seq = ?, turn_initiator = ?
                        WHERE user_id = ? AND id = ?
                    """, roundId, seq, TurnInitiator.USER.name(), userId, id);
                }

                // INCOMPLETE 判定：组内存在 assistant(tool_calls) 且无对应 tool 结果 → INCOMPLETE
                // 逐组扫描当前组消息（按 id 正序），找出 tool_calls 而无匹配 tool 的残留。
                List<Map<String, Object>> all = jdbc.queryForList("""
                    SELECT id, round_id, message_json FROM context_messages
                    WHERE user_id = ? AND round_id IS NOT NULL
                    ORDER BY seq ASC, id ASC
                """, userId);
                // 组 → 该组消息（正序）
                Map<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
                for (Map<String, Object> m : all) {
                    byGroup.computeIfAbsent((String) m.get("round_id"), k -> new ArrayList<>()).add(m);
                }
                for (Map.Entry<String, List<Map<String, Object>>> entry : byGroup.entrySet()) {
                    String roundId = entry.getKey();
                    if (roundId.startsWith("backfill-") && isIncompleteGroup(entry.getValue())) {
                        jdbc.update("""
                            UPDATE context_messages SET turn_status = ? WHERE user_id = ? AND round_id = ?
                        """, TurnStatus.INCOMPLETE.name(), userId, roundId);
                    }
                }

                backfilledUsers.add(userId);
                log.info("回填 Turn 字段完成 | userId={} | rows={}", userId, rows.size());
            } catch (Exception e) {
                log.warn("回填 Turn 字段失败，后续读取将退化为逐条 | userId={} | error={}", userId, e.getMessage());
            }
        }
    }

    /**
     * 判定一组（backfill 成组）消息是否 INCOMPLETE：
     * 存在 assistant(tool_calls) 且其 toolCallId 无对应 tool 消息。
     */
    private boolean isIncompleteGroup(List<Map<String, Object>> groupMsgs) {
        Set<String> toolCallIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (Map<String, Object> m : groupMsgs) {
            Message msg = deserialize((String) m.get("message_json"));
            if (msg == null) continue;
            if (msg.role() == MessageRole.ASSISTANT && msg.isToolCall() && msg.toolCallId() != null) {
                for (String id : msg.toolCallId().split(",")) {
                    toolCallIds.add(id.trim());
                }
            } else if (msg.role() == MessageRole.TOOL && msg.toolCallId() != null) {
                toolResultIds.add(msg.toolCallId());
            }
        }
        for (String id : toolCallIds) {
            if (!toolResultIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 每用户下一个 seq（当前最大 + 1）。 */
    private long nextSeq(String userId) {
        Long max = jdbc.queryForObject("""
            SELECT COALESCE(MAX(seq), 0) FROM context_messages WHERE user_id = ?
        """, Long.class, userId);
        return (max == null ? 0 : max) + 1;
    }

    /** 读取一条消息 + 其 Turn 维度（最新在前）。 */
    private List<MessageRow> loadTurnRows(String userId, int limit, int skipNewest) {
        String sql = """
            SELECT id, message_json, round_id, seq, turn_status, turn_initiator, created_at
            FROM context_messages
            WHERE user_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
        """;
        return jdbc.query(sql, (rs, rowNum) -> new MessageRow(
                rs.getLong("id"),
                rs.getString("message_json"),
                rs.getString("round_id"),
                rs.getLong("seq"),
                rs.getString("turn_status"),
                rs.getString("turn_initiator"),
                rs.getLong("created_at")), userId, limit, skipNewest);
    }

    /** 序列化单条消息。 */
    private String serialize(Message message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败", e);
            throw new IllegalStateException("序列化消息失败", e);
        }
    }

    /** 反序列化单条消息，失败返回 null。 */
    private Message deserialize(String json) {
        try {
            return mapper.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            log.warn("反序列化消息失败 | json={}", json);
            return null;
        }
    }

    private List<Message> deserializeChronologically(List<String> newestFirstJsons) {
        List<Message> result = new ArrayList<>();
        for (int i = newestFirstJsons.size() - 1; i >= 0; i--) {
            Message message = deserialize(newestFirstJsons.get(i));
            if (message != null && message.role() != null && message.content() != null) {
                result.add(message);
            }
        }
        return result;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** Turn 读取的原始行。 */
    private record MessageRow(long id, String messageJson, String roundId,
                              Long seq, String turnStatus, String turnInitiator,
                              long createdAt) {}

    // ==================== 内部方法 ====================

    private String resolveUserId() {
        return userExecutionContext.requireUserId();
    }

    private String resolveConversationId() {
        return userExecutionContext.currentConversationIdOrNull();
    }

    private long nextSeq(String userId, String conversationId) {
        Long max = jdbc.queryForObject("""
            SELECT COALESCE(MAX(seq), 0) FROM context_messages
            WHERE user_id = ? AND conversation_id = ?
        """, Long.class, userId, conversationId);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public void clear(String userId, String conversationId) {
        jdbc.update("DELETE FROM context_messages WHERE user_id = ? AND conversation_id = ?",
                userId, conversationId);
        log.debug("已清除 SQLite 对话上下文 | userId={} | conversationId={}",
                userId, conversationId);
    }

    private void appendCurrent(Message message) {
        String conversationId = resolveConversationId();
        if (conversationId == null) append(resolveUserId(), message);
        else append(resolveUserId(), conversationId, message);
    }

    private void append(String userId, String conversationId, Message message) {
        jdbc.update("""
            INSERT INTO context_messages (user_id, conversation_id, message_json)
            VALUES (?, ?, ?)
        """, userId, conversationId, serialize(message));
        jdbc.update("""
            DELETE FROM context_messages
            WHERE user_id = ? AND conversation_id = ? AND id NOT IN (
                SELECT id FROM context_messages
                WHERE user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
            )
        """, userId, conversationId, userId, conversationId, props.getMaxMessages());
    }
}
