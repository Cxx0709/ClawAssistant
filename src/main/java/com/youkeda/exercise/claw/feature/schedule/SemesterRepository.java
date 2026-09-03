package com.youkeda.exercise.claw.feature.schedule;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 学期 SQLite 持久化仓库
 *
 * <p>与 {@link CourseRepository} 共享同一数据库文件（{@code claw-schedule.db}），
 * 表名 {@code semester}，以 {@code userId} 作为隔离键。
 *
 * <p>学期数据决定了用户课程表的第一周日期，是周次计算的基础。
 */
@Repository
public class SemesterRepository {

    private static final Logger log = LoggerFactory.getLogger(SemesterRepository.class);

    private static final String TABLE_NAME = "semester";

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS semester (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id        TEXT NOT NULL,
                academic_year  INTEGER NOT NULL,
                term           TEXT NOT NULL,
                start_date     TEXT NOT NULL,
                source         TEXT NOT NULL DEFAULT 'AUTO_DETECT',
                created_time   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """;

    private static final String INDEX_USER = """
            CREATE INDEX IF NOT EXISTS idx_semester_user ON semester(user_id)
            """;

    /** 唯一索引：同一用户同一学期只能有一条记录 */
    private static final String UNIQUE_USER_TERM = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_semester_user_term ON semester(user_id, academic_year, term)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO semester
                (user_id, academic_year, term, start_date, source)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_USER = """
            SELECT * FROM semester WHERE user_id = ? ORDER BY academic_year DESC, created_time DESC
            """;

    private static final String SELECT_LATEST_BY_USER = """
            SELECT * FROM semester WHERE user_id = ? ORDER BY id DESC LIMIT 1
            """;

    private static final String SELECT_BY_ID = """
            SELECT * FROM semester WHERE id = ?
            """;

    private static final String SELECT_BY_USER_AND_TERM = """
            SELECT * FROM semester WHERE user_id = ? AND academic_year = ? AND term = ? ORDER BY id DESC LIMIT 1
            """;

    private static final String DELETE_BY_USER = """
            DELETE FROM semester WHERE user_id = ?
            """;

    private static final String DELETE_BY_ID = """
            DELETE FROM semester WHERE id = ? AND user_id = ?
            """;

    private static final String COUNT_BY_USER = """
            SELECT COUNT(*) FROM semester WHERE user_id = ?
            """;

    private static final String UPDATE_SQL = """
            UPDATE semester SET
                academic_year = ?, term = ?, start_date = ?, source = ?
            WHERE id = ? AND user_id = ?
            """;

    // 与 CourseRepository 共享同一数据库文件
    @Value("${schedule.db-path:./data/claw-schedule.db}")
    private String dbPath;

    public SemesterRepository() {
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(TABLE_DDL);
            stmt.execute(INDEX_USER);
            // 唯一索引迁移：已有重复数据时忽略（不会创建索引，但不影响功能）
            try {
                stmt.execute(UNIQUE_USER_TERM);
            } catch (SQLException e) {
                log.warn("唯一索引创建失败（可能已有重复数据），需手动清理 | path={} | error={}", dbPath, e.getMessage());
            }
            log.info("学期数据库表初始化完成 | table={} | path={}", TABLE_NAME, dbPath);
        } catch (SQLException e) {
            log.error("学期数据库表初始化失败 | path={}", dbPath, e);
            throw new RuntimeException("学期数据库初始化失败", e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 保存新学期
     *
     * @param semester 学期实体（不含 id）
     * @return 保存后的学期实体（含 id）
     */
    public SemesterEntity save(SemesterEntity semester) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, semester.getUserId());
            ps.setInt(2, semester.getAcademicYear());
            ps.setString(3, semester.getTerm());
            ps.setString(4, semester.getStartDateString());
            ps.setString(5, semester.getSource());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    semester.setId(rs.getLong(1));
                }
            }

            log.info("学期保存成功 | userId={} | display={} | startDate={} | source={}",
                    semester.getUserId(), semester.getDisplayName(),
                    semester.getStartDateString(), semester.getSource());

            // 回读 created_time
            if (semester.getId() != null) {
                SemesterEntity loaded = findById(semester.getId());
                if (loaded != null) {
                    semester.setCreatedTime(loaded.getCreatedTime());
                }
            }

            return semester;
        } catch (SQLException e) {
            log.error("学期保存失败 | userId={} | error={}", semester.getUserId(), e.getMessage(), e);
            throw new RuntimeException("学期保存失败", e);
        }
    }

    /**
     * 更新学期信息
     *
     * @param semester 学期实体（必须包含 id 和 userId）
     * @return 是否更新成功
     */
    public boolean update(SemesterEntity semester) {
        if (semester.getId() == null || semester.getUserId() == null) {
            return false;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_SQL)) {
            ps.setInt(1, semester.getAcademicYear());
            ps.setString(2, semester.getTerm());
            ps.setString(3, semester.getStartDateString());
            ps.setString(4, semester.getSource());
            ps.setLong(5, semester.getId());
            ps.setString(6, semester.getUserId());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("学期已更新 | id={} | userId={} | display={}",
                        semester.getId(), semester.getUserId(), semester.getDisplayName());
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("学期更新失败 | id={} | error={}", semester.getId(), e.getMessage(), e);
            return false;
        }
    }

    // ==================== 查询 ====================

    /**
     * 查询用户的所有学期（按 academic_year 倒序）
     *
     * @param userId 用户标识
     * @return 学期列表
     */
    public List<SemesterEntity> findByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER)) {
            ps.setString(1, userId);
            List<SemesterEntity> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapSemester(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户学期失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 查询用户最近一条学期
     *
     * @param userId 用户标识
     * @return 最近的学期，可能为空
     */
    public Optional<SemesterEntity> findLatestByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_LATEST_BY_USER)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSemester(rs));
                }
            }
        } catch (SQLException e) {
            log.error("查询用户最新学期失败 | userId={}", userId, e);
        }
        return Optional.empty();
    }

    /**
     * 查询用户特定学年的学期
     *
     * @param userId       用户标识
     * @param academicYear 学年（如 2026）
     * @param term         学期类型（SPRING / FALL）
     * @return 匹配的学期，可能为空
     */
    public Optional<SemesterEntity> findByUserIdAndTerm(String userId, int academicYear, String term) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_AND_TERM)) {
            ps.setString(1, userId);
            ps.setInt(2, academicYear);
            ps.setString(3, term);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSemester(rs));
                }
            }
        } catch (SQLException e) {
            log.error("按学年学期查询失败 | userId={} | year={} | term={}", userId, academicYear, term, e);
        }
        return Optional.empty();
    }

    /**
     * 根据 ID 查询学期
     */
    public SemesterEntity findById(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSemester(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询学期失败 | id={}", id, e);
        }
        return null;
    }

    /**
     * 统计用户学期数量
     */
    public int countByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_BY_USER)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.error("统计学期数量失败 | userId={}", userId, e);
        }
        return 0;
    }

    // ==================== 删除 ====================

    /**
     * 删除用户全部学期
     */
    public void deleteByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER)) {
            ps.setString(1, userId);
            int count = ps.executeUpdate();
            log.info("已删除用户学期 | userId={} | count={}", userId, count);
        } catch (SQLException e) {
            log.error("删除用户学期失败 | userId={}", userId, e);
        }
    }

    /**
     * 删除单条学期（含 userId 归属校验）
     */
    public boolean deleteById(Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_ID)) {
            ps.setLong(1, id);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除学期失败 | id={} | userId={}", id, userId, e);
            return false;
        }
    }

    // ==================== 内部方法 ====================

    private SemesterEntity mapSemester(ResultSet rs) throws SQLException {
        SemesterEntity s = new SemesterEntity();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getString("user_id"));
        s.setAcademicYear(rs.getInt("academic_year"));
        s.setTerm(rs.getString("term"));
        s.setStartDateFromString(rs.getString("start_date"));
        s.setSource(rs.getString("source"));
        s.setCreatedTime(rs.getString("created_time"));
        return s;
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