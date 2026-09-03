package com.youkeda.exercise.claw.feature.task.repository;

import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务 SQLite 持久化仓库
 *
 * <p>遵循 JDBC 直连 + WAL 模式。
 * 数据库文件：{@code ./data/claw-tasks.db}
 */
@Repository
public class ScheduledTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRepository.class);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== 建表 DDL ====================

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS scheduled_task (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id           TEXT NOT NULL,
                content           TEXT NOT NULL,
                trigger_type      TEXT NOT NULL DEFAULT 'DELAY',
                execute_time      TEXT NOT NULL,
                repeat_type       TEXT NOT NULL DEFAULT 'ONCE',
                repeat_interval   INTEGER DEFAULT 1,
                next_execute_time TEXT NOT NULL,
                status            TEXT NOT NULL DEFAULT 'ACTIVE',
                task_type         TEXT NOT NULL DEFAULT 'REMINDER',
                created_time      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                failure_count     INTEGER DEFAULT 0
            )
            """;

    // ==================== 迁移 SQL（兼容旧表） ====================

    private static final String ALTER_ADD_REPEAT_TYPE = """
            ALTER TABLE scheduled_task ADD COLUMN repeat_type TEXT NOT NULL DEFAULT 'ONCE'
            """;

    private static final String ALTER_ADD_FAILURE_COUNT = """
            ALTER TABLE scheduled_task ADD COLUMN failure_count INTEGER DEFAULT 0
            """;

    private static final String ALTER_ADD_REPEAT_INTERVAL = """
            ALTER TABLE scheduled_task ADD COLUMN repeat_interval INTEGER DEFAULT 1
            """;

    private static final String ALTER_ADD_NEXT_EXECUTE_TIME = """
            ALTER TABLE scheduled_task ADD COLUMN next_execute_time TEXT
            """;

    private static final String ALTER_ADD_TASK_TYPE = """
            ALTER TABLE scheduled_task ADD COLUMN task_type TEXT NOT NULL DEFAULT 'REMINDER'
            """;

    private static final String MIGRATE_NEXT_EXECUTE_TIME = """
            UPDATE scheduled_task SET next_execute_time = execute_time WHERE next_execute_time IS NULL
            """;

    // ==================== 索引 ====================

    private static final String INDEX_PENDING = """
            CREATE INDEX IF NOT EXISTS idx_task_pending
            ON scheduled_task(status, next_execute_time)
            """;

    // ==================== 插入 ====================

    private static final String INSERT_SQL = """
            INSERT INTO scheduled_task
                (user_id, content, trigger_type, execute_time,
                 repeat_type, repeat_interval, next_execute_time, status, task_type, created_time, failure_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // ==================== 查询 ====================

    private static final String COLUMNS = """
            id, user_id, content, trigger_type, execute_time,
            repeat_type, repeat_interval, next_execute_time, status, task_type, created_time, failure_count
            """;

    private static final String SELECT_PENDING_AND_DUE = """
            SELECT %s FROM scheduled_task
            WHERE status = 'ACTIVE' AND next_execute_time <= ?
            ORDER BY next_execute_time ASC
            """.formatted(COLUMNS);

    private static final String SELECT_BY_ID = """
            SELECT %s FROM scheduled_task WHERE id = ?
            """.formatted(COLUMNS);

    private static final String SELECT_BY_USER = """
            SELECT %s FROM scheduled_task WHERE user_id = ?
            ORDER BY created_time DESC
            """.formatted(COLUMNS);

    private static final String SELECT_BY_USER_AND_STATUS = """
            SELECT %s FROM scheduled_task WHERE user_id = ? AND status = ?
            ORDER BY created_time DESC
            """.formatted(COLUMNS);

    private static final String SELECT_BY_USER_AND_TYPE = """
            SELECT %s FROM scheduled_task WHERE user_id = ? AND task_type = ?
            ORDER BY created_time DESC
            """.formatted(COLUMNS);

    private static final String SELECT_BY_USER_AND_TYPE_AND_STATUS = """
            SELECT %s FROM scheduled_task WHERE user_id = ? AND task_type = ? AND status = ?
            ORDER BY created_time DESC
            """.formatted(COLUMNS);

    // ==================== 更新 ====================

    private static final String UPDATE_STATUS = """
            UPDATE scheduled_task SET status = ? WHERE id = ?
            """;

    private static final String UPDATE_STATUS_BY_USER = """
            UPDATE scheduled_task SET status = ? WHERE id = ? AND user_id = ?
            """;

    private static final String UPDATE_FAILURE_COUNT = """
            UPDATE scheduled_task SET failure_count = ? WHERE id = ?
            """;

    /** 原子 claim：ACTIVE → RUNNING，受影响行数=0 说明已被其他线程 claim（P0-2 防重复提交） */
    private static final String CLAIM_FOR_EXECUTION = """
            UPDATE scheduled_task SET status = 'RUNNING' WHERE id = ? AND status = 'ACTIVE'
            """;

    /** 启动时把残留 RUNNING 重置回 ACTIVE（进程可能执行中被杀） */
    private static final String RESET_STALE_RUNNING = """
            UPDATE scheduled_task SET status = 'ACTIVE' WHERE status = 'RUNNING'
            """;

    /** 执行后归位：RUNNING → ACTIVE。仅当任务仍处于 RUNNING 时生效，
     *  避免覆盖执行期间被用户取消/标记 DONE 的任务（P0-2 执行态释放） */
    private static final String RELEASE_FROM_RUNNING = """
            UPDATE scheduled_task SET status = 'ACTIVE' WHERE id = ? AND status = 'RUNNING'
            """;

    private static final String UPDATE_TASK = """
            UPDATE scheduled_task SET
                content = ?,
                execute_time = ?,
                repeat_type = ?,
                repeat_interval = ?,
                next_execute_time = ?
            WHERE id = ? AND user_id = ?
            """;

    private static final String UPDATE_NEXT_EXECUTE_TIME = """
            UPDATE scheduled_task SET next_execute_time = ? WHERE id = ?
            """;

    @Value("${task.db-path:./data/claw-tasks.db}")
    private String dbPath;

    public ScheduledTaskRepository() {
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(TABLE_DDL);
            // 兼容旧表：尝试添加新列（列已存在时忽略错误）
            try { stmt.execute(ALTER_ADD_REPEAT_TYPE); } catch (SQLException ignored) {}
            try { stmt.execute(ALTER_ADD_REPEAT_INTERVAL); } catch (SQLException ignored) {}
            try { stmt.execute(ALTER_ADD_NEXT_EXECUTE_TIME); } catch (SQLException ignored) {}
            try { stmt.execute(ALTER_ADD_TASK_TYPE); } catch (SQLException ignored) {}
            try { stmt.execute(ALTER_ADD_FAILURE_COUNT); } catch (SQLException ignored) {}
            // 迁移：旧记录的 next_execute_time 设为 execute_time
            try (Statement migrateStmt = conn.createStatement()) {
                migrateStmt.execute(MIGRATE_NEXT_EXECUTE_TIME);
            }
            stmt.execute(INDEX_PENDING);
            log.info("定时任务表初始化完成 | path={}", dbPath);
        } catch (SQLException e) {
            log.error("定时任务表初始化失败 | path={}", dbPath, e);
            throw new RuntimeException("定时任务表初始化失败", e);
        }
        // 启动时恢复上次进程可能残留的 RUNNING 任务（执行中被杀导致状态未回收）
        resetStaleRunning();
    }

    /**
     * 启动时将残留 RUNNING 任务重置回 ACTIVE（进程执行中被杀时状态未回收）。
     */
    public void resetStaleRunning() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(RESET_STALE_RUNNING)) {
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.warn("检测到 {} 个残留 RUNNING 任务，已重置为 ACTIVE", rows);
            }
        } catch (SQLException e) {
            log.error("重置残留 RUNNING 任务失败 | error={}", e.getMessage(), e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 保存定时任务
     */
    public ScheduledTask save(ScheduledTask task) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, task.getUserId());
            ps.setString(2, task.getContent());
            ps.setString(3, task.getTriggerType());
            ps.setString(4, task.getExecuteTimeAsString());
            ps.setString(5, task.getRepeatType() != null ? task.getRepeatType() : ScheduledTask.REPEAT_TYPE_ONCE);
            ps.setInt(6, task.getRepeatInterval() != null ? task.getRepeatInterval() : 1);
            ps.setString(7, task.getNextExecuteTimeAsString());
            ps.setString(8, task.getStatus());
            ps.setString(9, task.getTaskType());
            ps.setString(10, task.getCreatedTimeAsString());
            ps.setInt(11, task.getFailureCount());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    task.setId(rs.getLong(1));
                }
            }

            log.info("定时任务已保存 | id={} | userId={} | content={} | repeat={} | executeTime={}",
                    task.getId(), task.getUserId(), task.getContent(),
                    task.getRepeatType(), task.getExecuteTimeAsString());
            return task;
        } catch (SQLException e) {
            log.error("保存定时任务失败 | userId={} | error={}", task.getUserId(), e.getMessage(), e);
            throw new RuntimeException("保存定时任务失败", e);
        }
    }

    // ==================== 查询 ====================

    /**
     * 原子 claim：ACTIVE → RUNNING。
     *
     * @return true 表示本线程成功抢到该任务（其他人未 claim）；false 表示已被其他线程 claim，跳过
     */
    public boolean claimForExecution(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(CLAIM_FOR_EXECUTION)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("任务 claim 失败 | id={} | error={}", id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 执行后释放：RUNNING → ACTIVE。
     *
     * <p>仅当任务仍处于 RUNNING 时生效（P0-2 条件更新）：周期任务执行完必须归位 ACTIVE，
     * 否则 {@link #findPendingAndDue()} 的 {@code status='ACTIVE'} 条件永远查不到它，
     * 任务会停在 RUNNING 停摆。条件限定避免覆盖执行期间被取消/标记 DONE 的任务。
     */
    public boolean releaseFromRunning(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(RELEASE_FROM_RUNNING)) {
            ps.setLong(1, id);
            boolean released = ps.executeUpdate() > 0;
            if (released) {
                log.debug("周期任务执行完已归位 ACTIVE | id={}", id);
            }
            return released;
        } catch (SQLException e) {
            log.error("周期任务执行完归位失败 | id={} | error={}", id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 查找到期任务（按 next_execute_time）
     */
    public List<ScheduledTask> findPendingAndDue() {
        String now = LocalDateTime.now().format(DTF);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PENDING_AND_DUE)) {
            ps.setString(1, now);
            List<ScheduledTask> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTask(rs));
                }
            }
            if (!results.isEmpty()) {
                log.info("查询到 {} 个到期任务", results.size());
            }
            return results;
        } catch (SQLException e) {
            log.error("查询到期任务失败", e);
            return List.of();
        }
    }

    /**
     * 根据 ID 查询任务
     */
    public ScheduledTask findById(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTask(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询任务失败 | id={}", id, e);
        }
        return null;
    }

    /**
     * 查询指定用户的所有任务
     */
    public List<ScheduledTask> findByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER)) {
            ps.setString(1, userId);
            List<ScheduledTask> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTask(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户任务失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 查询指定用户的指定状态的任务
     */
    public List<ScheduledTask> findByUserIdAndStatus(String userId, String status) {
        String sql = (status != null && !status.isEmpty()) ? SELECT_BY_USER_AND_STATUS : SELECT_BY_USER;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            if (status != null && !status.isEmpty()) {
                ps.setString(2, status);
            }
            List<ScheduledTask> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTask(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户任务失败 | userId={} | status={}", userId, status, e);
            return List.of();
        }
    }

    /**
     * 查询指定用户的指定类型的任务
     *
     * @param userId   用户标识
     * @param taskType 任务类型（REMINDER / AGENT）
     */
    public List<ScheduledTask> findByUserIdAndType(String userId, String taskType) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_AND_TYPE)) {
            ps.setString(1, userId);
            ps.setString(2, taskType);
            List<ScheduledTask> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTask(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户任务失败 | userId={} | taskType={}", userId, taskType, e);
            return List.of();
        }
    }

    /**
     * 查询指定用户的指定类型和指定状态的任务
     *
     * @param userId   用户标识
     * @param taskType 任务类型（REMINDER / AGENT）
     * @param status   任务状态
     */
    public List<ScheduledTask> findByUserIdAndTypeAndStatus(String userId, String taskType, String status) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_AND_TYPE_AND_STATUS)) {
            ps.setString(1, userId);
            ps.setString(2, taskType);
            ps.setString(3, status);
            List<ScheduledTask> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTask(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户任务失败 | userId={} | taskType={} | status={}", userId, taskType, status, e);
            return List.of();
        }
    }

    // ==================== 更新 ====================

    /**
     * 更新整个任务（修改内容、时间、周期）
     *
     * @param task 任务实体（必须包含 id 和 userId）
     * @return 是否更新成功
     */
    public boolean updateTask(ScheduledTask task) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_TASK)) {
            ps.setString(1, task.getContent());
            ps.setString(2, task.getExecuteTimeAsString());
            ps.setString(3, task.getRepeatType() != null ? task.getRepeatType() : ScheduledTask.REPEAT_TYPE_ONCE);
            ps.setInt(4, task.getRepeatInterval() != null ? task.getRepeatInterval() : 1);
            ps.setString(5, task.getNextExecuteTimeAsString());
            ps.setLong(6, task.getId());
            ps.setString(7, task.getUserId());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("定时任务已更新 | id={} | userId={} | content={} | executeTime={} | repeat={}",
                        task.getId(), task.getUserId(), task.getContent(),
                        task.getExecuteTimeAsString(), task.getRepeatType());
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("更新定时任务失败 | id={} | error={}", task.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 更新下次执行时间
     */
    public boolean updateNextExecuteTime(Long id, LocalDateTime nextExecuteTime) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_NEXT_EXECUTE_TIME)) {
            ps.setString(1, nextExecuteTime.format(DTF));
            ps.setLong(2, id);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            log.error("更新下次执行时间失败 | id={}", id, e);
            return false;
        }
    }

    // ==================== 状态更新 ====================

    public boolean markDone(Long id) {
        return updateStatus(id, ScheduledTask.STATUS_DONE);
    }

    public boolean markFailed(Long id, String errorMsg) {
        boolean updated = updateStatus(id, ScheduledTask.STATUS_FAILED);
        if (updated) {
            log.warn("定时任务执行失败 | id={} | error={}", id, errorMsg);
        }
        return updated;
    }

    /**
     * 周期任务失败：连续失败次数 +1（不改变状态，由调度器决定是否达阈值停止）。
     *
     * @return 递增后的失败次数；更新失败返回 -1
     */
    public int incrementFailureCount(Long id) {
        ScheduledTask task = findById(id);
        if (task == null) return -1;
        int newCount = task.getFailureCount() + 1;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_FAILURE_COUNT)) {
            ps.setInt(1, newCount);
            ps.setLong(2, id);
            ps.executeUpdate();
            return newCount;
        } catch (SQLException e) {
            log.error("递增失败次数失败 | id={} | error={}", id, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 周期任务执行成功后清零失败次数。
     */
    public boolean resetFailureCount(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_FAILURE_COUNT)) {
            ps.setInt(1, 0);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("清零失败次数失败 | id={} | error={}", id, e.getMessage(), e);
            return false;
        }
    }

    public boolean markCancelled(Long id) {
        return updateStatus(id, ScheduledTask.STATUS_CANCELLED);
    }

    public boolean markCancelled(Long id, String userId) {
        return updateStatusByUser(id, userId, ScheduledTask.STATUS_CANCELLED);
    }

    /**
     * 暂停 Agent 任务（ACTIVE → PAUSED）
     */
    public boolean markPaused(Long id, String userId) {
        boolean updated = updateStatusByUser(id, userId, ScheduledTask.STATUS_PAUSED);
        if (updated) {
            log.info("Agent 任务已暂停 | id={} | userId={}", id, userId);
        }
        return updated;
    }

    /**
     * 恢复 Agent 任务（PAUSED → ACTIVE）
     */
    public boolean markResumed(Long id, String userId) {
        boolean updated = updateStatusByUser(id, userId, ScheduledTask.STATUS_ACTIVE);
        if (updated) {
            log.info("Agent 任务已恢复 | id={} | userId={}", id, userId);
        }
        return updated;
    }

    // ==================== 内部方法 ====================

    private boolean updateStatus(Long id, String status) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_STATUS)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.debug("定时任务状态已更新 | id={} | status={}", id, status);
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("更新任务状态失败 | id={} | status={}", id, status, e);
            return false;
        }
    }

    /**
     * 带 userId 归属校验的状态更新
     *
     * @param id     任务 ID
     * @param userId 用户标识
     * @param status 目标状态
     * @return 是否更新成功
     */
    private boolean updateStatusByUser(Long id, String userId, String status) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_STATUS_BY_USER)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.setString(3, userId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.debug("定时任务状态已更新 | id={} | userId={} | status={}", id, userId, status);
            } else {
                log.warn("状态更新失败：任务不存在或不属于该用户 | id={} | userId={}", id, userId);
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("更新任务状态失败 | id={} | userId={} | status={}", id, userId, status, e);
            return false;
        }
    }

    private ScheduledTask mapTask(ResultSet rs) throws SQLException {
        ScheduledTask task = new ScheduledTask();
        task.setId(rs.getLong("id"));
        task.setUserId(rs.getString("user_id"));
        task.setContent(rs.getString("content"));
        task.setTriggerType(rs.getString("trigger_type"));
        task.setExecuteTimeFromString(rs.getString("execute_time"));

        String repeatType = rs.getString("repeat_type");
        task.setRepeatType(repeatType != null ? repeatType : ScheduledTask.REPEAT_TYPE_ONCE);
        task.setRepeatInterval(rs.getObject("repeat_interval") != null ? rs.getInt("repeat_interval") : 1);

        task.setNextExecuteTimeFromString(rs.getString("next_execute_time"));

        task.setStatus(rs.getString("status"));

        task.setFailureCount(rs.getObject("failure_count") != null
                ? rs.getInt("failure_count") : 0);

        String taskType = rs.getString("task_type");
        task.setTaskType(taskType != null ? taskType : ScheduledTask.TASK_TYPE_REMINDER);

        task.setCreatedTimeFromString(rs.getString("created_time"));
        return task;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        return conn;
    }

    private void ensureDirectoryExists() {
        File dbFile = new File(dbPath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                log.info("已创建数据库目录 | path={}", parentDir.getAbsolutePath());
            }
        }
    }
}