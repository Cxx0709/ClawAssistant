package com.youkeda.exercise.claw.infrastructure.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.util.List;
import java.util.UUID;
import com.youkeda.exercise.claw.web.conversation.ConversationTitleGenerator;

/**
 * SQLite 数据库初始化器
 *
 * 应用启动时自动创建表结构，清理过期记录
 */
@Component
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class SqliteDatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqliteDatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    public SqliteDatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        ensureDatabaseDirectory();
        log.info("正在初始化 SQLite 数据库...");
        createTables();
        cleanExpiredRecords();
        log.info("SQLite 数据库初始化完成");
    }

    private void createTables() {
        // Web 历史对话。标题与归档状态与消息分开，列表无需扫描消息表。
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '新对话',
                title_source TEXT NOT NULL DEFAULT 'AUTO',
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                last_message_preview TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_conversations_user_updated
            ON conversations(user_id, archived, pinned, updated_at DESC)
        """);
        addColumn("conversations", "last_message_at", "INTEGER");
        addColumn("conversations", "metadata_updated_at", "INTEGER");
        addColumn("conversations", "deleted_at", "INTEGER");
        addColumn("conversations", "role_id", "TEXT");
        jdbcTemplate.update("""
            UPDATE conversations
            SET last_message_at = COALESCE(last_message_at, updated_at),
                metadata_updated_at = COALESCE(metadata_updated_at, updated_at)
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_conversations_user_state_time
            ON conversations(user_id, deleted_at, archived, pinned, last_message_at DESC)
        """);

        // 创建对话上下文表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS context_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                message_json TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);

        // 创建索引
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_context_user_id ON context_messages(user_id)
        """);

        // === 迁移（ADR Phase 1B）：为 context_messages 添加 Turn 维度列 ===
        // 幂等：旧库缺列时 ALTER ADD，新库/已迁移库跳过
        try {
            jdbcTemplate.execute("ALTER TABLE context_messages ADD COLUMN round_id TEXT");
            log.info("DB迁移完成：context_messages 添加 round_id 列");
        } catch (Exception e) {
            log.debug("context_messages.round_id 列已存在，跳过迁移");
        }
        try {
            jdbcTemplate.execute("ALTER TABLE context_messages ADD COLUMN seq INTEGER");
            log.info("DB迁移完成：context_messages 添加 seq 列");
        } catch (Exception e) {
            log.debug("context_messages.seq 列已存在，跳过迁移");
        }
        try {
            jdbcTemplate.execute("ALTER TABLE context_messages ADD COLUMN turn_status TEXT");
            log.info("DB迁移完成：context_messages 添加 turn_status 列");
        } catch (Exception e) {
            log.debug("context_messages.turn_status 列已存在，跳过迁移");
        }
        try {
            jdbcTemplate.execute("ALTER TABLE context_messages ADD COLUMN turn_initiator TEXT");
            log.info("DB迁移完成：context_messages 添加 turn_initiator 列");
        } catch (Exception e) {
            log.debug("context_messages.turn_initiator 列已存在，跳过迁移");
        }
        try {
            jdbcTemplate.execute("ALTER TABLE context_messages ADD COLUMN conversation_id TEXT");
            log.info("DB迁移完成：context_messages 添加 conversation_id 列");
        } catch (Exception e) {
            log.debug("context_messages.conversation_id 列已存在，跳过迁移");
        }
        migrateLegacyConversations();
        // Turn 维度索引：seq 每用户单调，query by (user_id, seq)
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_context_user_seq ON context_messages(user_id, seq)");
        } catch (Exception e) {
            log.debug("idx_context_user_seq 索引已存在，跳过");
        }
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_context_conversation_seq
            ON context_messages(user_id, conversation_id, seq)
        """);

        // 可见聊天记录与模型上下文解耦：保留附件、工具轨迹和生成状态。
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                attachments_json TEXT NOT NULL DEFAULT '[]',
                artifacts_json TEXT NOT NULL DEFAULT '[]',
                tool_trace_json TEXT NOT NULL DEFAULT '[]',
                skills_json TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL DEFAULT 'COMPLETED',
                run_id TEXT,
                error_text TEXT,
                total_ms INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_time
            ON chat_messages(user_id, conversation_id, created_at DESC, id DESC)
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_chat_messages_run ON chat_messages(user_id, run_id)
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chat_runs (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                user_message_id TEXT NOT NULL,
                assistant_message_id TEXT NOT NULL,
                status TEXT NOT NULL,
                draft_content TEXT NOT NULL DEFAULT '',
                tool_trace_json TEXT NOT NULL DEFAULT '[]',
                skills_json TEXT NOT NULL DEFAULT '[]',
                artifacts_json TEXT NOT NULL DEFAULT '[]',
                error_text TEXT,
                started_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                finished_at INTEGER
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_chat_runs_conversation
            ON chat_runs(user_id, conversation_id, updated_at DESC)
        """);
        long recoveryTime = System.currentTimeMillis();
        jdbcTemplate.update("""
            UPDATE chat_runs SET status = 'FAILED',
                error_text = '应用重启，已保留生成到中断前的内容',
                updated_at = ?, finished_at = ?
            WHERE status = 'STREAMING'
        """, recoveryTime, recoveryTime);
        jdbcTemplate.update("""
            UPDATE chat_messages SET status = 'FAILED',
                error_text = '应用重启，已保留生成到中断前的内容', updated_at = ?
            WHERE status = 'STREAMING'
        """, recoveryTime);
        createMessageSearchTable();
        migrateLegacyVisibleMessages();
        backfillConversationTitles();

        // 创建旅游方案草稿表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS travel_plans (
                user_id TEXT PRIMARY KEY,
                plan_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);

        // === 迁移（ADR Phase 2）：Agent 执行状态表（PlanState 落库）===
        // 单用户单行：当前 Agent 的多步任务执行状态，重启后可恢复。
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_plans (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                plan_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);

        // === 迁移（ADR Phase 3）：对话摘要表（增量 covered_until_seq 锚点）===
        // 单用户单行：长对话早期轮次的 LLM 摘要，随轮次推进增量合并。
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summary (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                summary_text TEXT NOT NULL,
                covered_until_seq INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summaries (
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                summary_text TEXT NOT NULL,
                covered_until_seq INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                PRIMARY KEY (user_id, conversation_id)
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_agent_plans (
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                plan_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                PRIMARY KEY (user_id, conversation_id)
            )
        """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_travel_plans (
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                plan_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                PRIMARY KEY (user_id, conversation_id)
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

        // 创建校园配置表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS campus_config (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                school      TEXT NOT NULL,
                class_name  TEXT NOT NULL DEFAULT '',
                enabled     INTEGER NOT NULL DEFAULT 1,
                extra_config TEXT,
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);

        // 创建待确认通知表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS campus_pending_ask (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                notice_type   TEXT NOT NULL,
                question      TEXT NOT NULL,
                answer        TEXT DEFAULT '',
                status        TEXT DEFAULT 'PENDING',
                asked_at      INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                answered_at   INTEGER
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_type ON campus_pending_ask(notice_type, status)
        """);

        // 创建技能会话表
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

        // === 迁移：为 campus_pending_ask 添加 source 列（默认 'EXAM' 兼容旧数据） ===
        try {
            jdbcTemplate.execute("ALTER TABLE campus_pending_ask ADD COLUMN source TEXT NOT NULL DEFAULT 'EXAM'");
            log.info("DB迁移完成：campus_pending_ask 添加 source 列");
        } catch (Exception e) {
            log.debug("campus_pending_ask.source 列已存在，跳过迁移");
        }

        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_pending_source_type ON campus_pending_ask(source, notice_type, status)");
        } catch (Exception e) {
            log.debug("idx_pending_source_type 索引已存在，跳过");
        }

        // === 动漫通知表 ===
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS anime_subscription (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                anilist_id  INTEGER NOT NULL UNIQUE,
                title       TEXT NOT NULL,
                title_ja    TEXT DEFAULT '',
                cover_url   TEXT DEFAULT '',
                status      TEXT DEFAULT 'RELEASING',
                genres      TEXT DEFAULT '[]',
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS anime_schedule (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                anilist_id  INTEGER NOT NULL,
                episode     INTEGER NOT NULL,
                airing_at   INTEGER NOT NULL,
                notified    INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                UNIQUE(anilist_id, episode)
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_anime_schedule_airing
            ON anime_schedule(airing_at, notified)
        """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS anime_reminder_task (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                anilist_id  INTEGER NOT NULL,
                episode     INTEGER NOT NULL,
                remind_time INTEGER NOT NULL,
                status      TEXT NOT NULL DEFAULT 'PENDING',
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_reminder_status_time
            ON anime_reminder_task(status, remind_time)
        """);

        log.debug("数据库表结构创建完成");
    }

    private void cleanExpiredRecords() {
        // 清理 30 天前的团建方案（团建方案保留更长时间）
        long planExpireTime = System.currentTimeMillis() / 1000 - 30 * 24 * 3600;
        int planDeleted = jdbcTemplate.update(
            "DELETE FROM travel_plans WHERE updated_at < ?", planExpireTime);
        if (planDeleted > 0) {
            log.info("已清理 {} 条过期旅游方案", planDeleted);
        }
        long trashExpireTime = System.currentTimeMillis() / 1000 - 30L * 24 * 3600;
        List<String> expiredConversationIds = jdbcTemplate.queryForList(
                "SELECT id FROM conversations WHERE deleted_at IS NOT NULL AND deleted_at < ?",
                String.class, trashExpireTime);
        for (String id : expiredConversationIds) {
            jdbcTemplate.update("DELETE FROM chat_message_search WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM chat_messages WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM chat_runs WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM context_messages WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM conversation_summaries WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM conversation_agent_plans WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM conversation_travel_plans WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM conversation_skill_sessions WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id);
        }
    }

    /** 将旧版用户级消息安全归入一个可见的“历史对话”。 */
    private void migrateLegacyConversations() {
        List<String> userIds = jdbcTemplate.queryForList("""
            SELECT DISTINCT user_id FROM context_messages
            WHERE conversation_id IS NULL OR conversation_id = ''
        """, String.class);
        for (String userId : userIds) {
            List<String> existing = jdbcTemplate.queryForList("""
                SELECT id FROM conversations WHERE user_id = ? ORDER BY created_at ASC LIMIT 1
            """, String.class, userId);
            String conversationId;
            if (existing.isEmpty()) {
                conversationId = UUID.randomUUID().toString();
                long now = System.currentTimeMillis() / 1000;
                jdbcTemplate.update("""
                    INSERT INTO conversations
                    (id, user_id, title, title_source, last_message_preview, created_at, updated_at)
                    VALUES (?, ?, '历史对话', 'AUTO', '', ?, ?)
                """, conversationId, userId, now, now);
            } else {
                conversationId = existing.get(0);
            }
            int updated = jdbcTemplate.update("""
                UPDATE context_messages SET conversation_id = ?
                WHERE user_id = ? AND (conversation_id IS NULL OR conversation_id = '')
            """, conversationId, userId);
            if (updated > 0) {
                log.info("已将 {} 条旧消息归入历史对话 | userId={}", updated, userId);
            }
        }
    }

    private void addColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("DB迁移完成：{}.{}", table, column);
        } catch (Exception e) {
            log.debug("{}.{} 列已存在，跳过迁移", table, column);
        }
    }

    private void createMessageSearchTable() {
        try {
            jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS chat_message_search USING fts5(
                    message_id UNINDEXED, conversation_id UNINDEXED, user_id UNINDEXED,
                    content, tokenize='trigram'
                )
            """);
        } catch (Exception trigramUnavailable) {
            log.info("SQLite trigram 分词不可用，回退到 unicode61");
            jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS chat_message_search USING fts5(
                    message_id UNINDEXED, conversation_id UNINDEXED, user_id UNINDEXED,
                    content, tokenize='unicode61'
                )
            """);
        }
    }

    /** 旧库只保存模型上下文；首次升级时复制其中可见的用户/助手消息。 */
    private void migrateLegacyVisibleMessages() {
        jdbcTemplate.update("""
            INSERT OR IGNORE INTO chat_messages
            (id, user_id, conversation_id, role, content, status, created_at, updated_at)
            SELECT 'legacy-' || id, user_id, conversation_id,
                   json_extract(message_json, '$.role'),
                   COALESCE(json_extract(message_json, '$.content'), ''),
                   'COMPLETED', created_at, created_at
            FROM context_messages
            WHERE conversation_id IS NOT NULL
              AND (json_extract(message_json, '$.role') = 'user'
                   OR (json_extract(message_json, '$.role') = 'assistant'
                       AND json_extract(message_json, '$.toolCallId') IS NULL))
        """);
        jdbcTemplate.update("""
            INSERT INTO chat_message_search(message_id, conversation_id, user_id, content)
            SELECT m.id, m.conversation_id, m.user_id, m.content
            FROM chat_messages m
            WHERE NOT EXISTS (
                SELECT 1 FROM chat_message_search s WHERE s.message_id = m.id
            )
        """);
    }

    /** 仅替换系统占位标题；用户手动命名的对话永远不覆盖。 */
    private void backfillConversationTitles() {
        List<LegacyTitleCandidate> candidates = jdbcTemplate.query("""
            SELECT c.id,
                   (SELECT m.content FROM chat_messages m
                    WHERE m.user_id = c.user_id AND m.conversation_id = c.id AND m.role = 'user'
                    ORDER BY m.created_at ASC, m.id ASC LIMIT 1) AS first_message
            FROM conversations c
            WHERE c.title_source = 'AUTO' AND c.title IN ('历史对话', '新对话')
        """, (rs, rowNum) -> new LegacyTitleCandidate(
                rs.getString("id"), rs.getString("first_message")));
        for (LegacyTitleCandidate candidate : candidates) {
            if (candidate.firstMessage() == null || candidate.firstMessage().isBlank()) continue;
            String title = ConversationTitleGenerator.fromMessage(candidate.firstMessage());
            jdbcTemplate.update("""
                UPDATE conversations SET title = ?,
                    last_message_preview = CASE WHEN last_message_preview = '' THEN ? ELSE last_message_preview END
                WHERE id = ? AND title_source = 'AUTO'
            """, title, truncate(candidate.firstMessage(), 120), candidate.id());
        }
    }

    private static String truncate(String value, int max) {
        String cleaned = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private record LegacyTitleCandidate(String id, String firstMessage) {}

    /**
     * 确保 SQLite 数据库文件所在目录存在
     */
    private void ensureDatabaseDirectory() {
        // jdbc:sqlite:data/claw.db → data/claw.db
        String path = datasourceUrl.replace("jdbc:sqlite:", "");
        File dbFile = new File(path);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                log.info("已创建数据库目录: {}", parentDir.getAbsolutePath());
            }
        }
    }
}
