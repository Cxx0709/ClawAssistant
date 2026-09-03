package com.youkeda.exercise.claw.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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

    public SqliteConversationSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversationSummary get() {
        try {
            return jdbc.queryForObject("""
                SELECT summary_text, covered_until_seq FROM conversation_summary WHERE id = 1
            """, (rs, rowNum) -> new ConversationSummary(
                    rs.getString("summary_text"), rs.getLong("covered_until_seq")));
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
            jdbc.update("""
                INSERT OR REPLACE INTO conversation_summary (id, summary_text, covered_until_seq, updated_at)
                VALUES (1, ?, ?, ?)
            """, summary.text(), summary.coveredUntilSeq(), now);
            log.debug("对话摘要已落库 | coveredUntilSeq={}", summary.coveredUntilSeq());
        } catch (Exception e) {
            log.error("保存对话摘要失败 | error={}", e.getMessage());
        }
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM conversation_summary WHERE id = 1");
    }
}
