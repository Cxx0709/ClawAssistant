package com.youkeda.exercise.claw.feature.campus.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.dao.EmptyResultDataAccessException;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class PendingAskStore {

    private static final Logger log = LoggerFactory.getLogger(PendingAskStore.class);

    private final JdbcTemplate jdbc;

    public PendingAskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ========== 新方法（统一通知框架使用，含 source 字段）==========

    /** 查指定来源+类型的最新一条回答 */
    public Optional<String> findLatestAnswer(String source, String noticeType) {
        try {
            String answer = jdbc.queryForObject("""
                SELECT answer FROM campus_pending_ask
                WHERE source = ? AND notice_type = ? AND status = 'ANSWERED'
                ORDER BY asked_at DESC LIMIT 1
                """, String.class, source, noticeType);
            return Optional.ofNullable(answer);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("查询最新回答失败 | source={} | noticeType={}", source, noticeType, e);
            return Optional.empty();
        }
    }

    public void save(String source, String noticeType, String question, String status) {
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            INSERT INTO campus_pending_ask (source, notice_type, question, status, asked_at)
            VALUES (?, ?, ?, ?, ?)
            """, source, noticeType, question, status, now);
    }

    public void updateAnswer(String source, String noticeType, String answer) {
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            UPDATE campus_pending_ask SET answer=?, status='ANSWERED', answered_at=?
            WHERE source=? AND notice_type=? AND status='PENDING'
            """, answer, now, source, noticeType);
    }

    // ========== 旧方法保留（委托新方法，向后兼容）==========

    /** @deprecated 使用 {@link #findLatestAnswer(String, String)} */
    @Deprecated
    public Optional<String> findLatestAnswer(String noticeType) {
        return findLatestAnswer("EXAM", noticeType);
    }

    /** @deprecated 使用 {@link #save(String, String, String, String)} */
    @Deprecated
    public void save(String noticeType, String question, String status) {
        save("EXAM", noticeType, question, status);
    }

    /** @deprecated 使用 {@link #updateAnswer(String, String, String)} */
    @Deprecated
    public void updateAnswer(String noticeType, String answer) {
        updateAnswer("EXAM", noticeType, answer);
    }
}
