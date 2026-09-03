package com.youkeda.exercise.claw.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.youkeda.exercise.claw.identity.UserExecutionContext;

/**
 * SQLite 版对话摘要存储（ADR §9/Phase 3）。
 *
 * <p>单用户单行：{@code conversation_summary} 表固定 id=1（CHECK 约束保证单行）。
 * 存摘要文本 + covered_until_seq 锚点。
 */
@Component
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class SqliteConversationSummaryStore implements ConversationSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteConversationSummaryStore.class);

    private final JdbcTemplate jdbc;
    private final UserExecutionContext executionContext;

    public SqliteConversationSummaryStore(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public SqliteConversationSummaryStore(JdbcTemplate jdbc, UserExecutionContext executionContext) {
        this.jdbc = jdbc;
        this.executionContext = executionContext;
    }

    @Override
    public ConversationSummary get() {
        try {
            String conversationId = conversationId();
            String sql = conversationId == null ? """
                SELECT summary_text, covered_until_seq FROM conversation_summary WHERE id = 1
            """ : """
                SELECT summary_text, covered_until_seq FROM conversation_summaries
                WHERE user_id = ? AND conversation_id = ?
            """;
            Object[] args = conversationId == null ? new Object[0]
                    : new Object[]{executionContext.requireUserId(), conversationId};
            return jdbc.queryForObject(sql, (rs, rowNum) -> new ConversationSummary(
                    rs.getString("summary_text"), rs.getLong("covered_until_seq")), args);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            log.warn("读取对话摘要失败 | error={}", e.getMessage());
            return null;
        }
    }

    @Override
    public void save(ConversationSummary summary) {
        try {
            long now = System.currentTimeMillis() / 1000;
            String conversationId = conversationId();
            if (conversationId == null) {
                jdbc.update("""
                    INSERT OR REPLACE INTO conversation_summary
                    (id, summary_text, covered_until_seq, updated_at) VALUES (1, ?, ?, ?)
                """, summary.text(), summary.coveredUntilSeq(), now);
            } else {
                jdbc.update("""
                    INSERT OR REPLACE INTO conversation_summaries
                    (user_id, conversation_id, summary_text, covered_until_seq, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                """, executionContext.requireUserId(), conversationId,
                        summary.text(), summary.coveredUntilSeq(), now);
            }
            log.debug("对话摘要已落库 | coveredUntilSeq={}", summary.coveredUntilSeq());
        } catch (Exception e) {
            log.error("保存对话摘要失败 | error={}", e.getMessage());
        }
    }

    @Override
    public void clear() {
        String conversationId = conversationId();
        if (conversationId == null) jdbc.update("DELETE FROM conversation_summary WHERE id = 1");
        else jdbc.update("""
            DELETE FROM conversation_summaries WHERE user_id = ? AND conversation_id = ?
        """, executionContext.requireUserId(), conversationId);
    }

    private String conversationId() {
        return executionContext == null ? null : executionContext.currentConversationIdOrNull();
    }
}
