package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.domain.schedule.SchoolEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 学校作息配置 SQLite 持久化仓库
 *
 * <p>管理三张表：
 * <ul>
 *   <li>{@code schools} — 学校基本信息</li>
 *   <li>{@code school_schedule_config} — 学校节次时间表</li>
 *   <li>{@code break_config} — 学校课间休息配置</li>
 * </ul>
 *
 * <p>与 {@link CourseRepository} 共用同一 {@code claw-schedule.db} 数据库文件。
 * 系统初始化时自动创建一个"默认大学（Default University）"作为 fallback。
 */
@Repository
public class SchoolScheduleConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(SchoolScheduleConfigRepository.class);

    // ==================== DDL ====================

    private static final String CREATE_TABLE_SCHOOLS = """
            CREATE TABLE IF NOT EXISTS schools (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                school_name   TEXT NOT NULL,
                school_code   TEXT,
                created_at    TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """;

    private static final String CREATE_TABLE_SCHEDULE = """
            CREATE TABLE IF NOT EXISTS school_schedule_config (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                school_id     INTEGER NOT NULL,
                period_number INTEGER NOT NULL,
                start_time    TEXT NOT NULL,
                end_time      TEXT NOT NULL,
                duration      INTEGER,
                created_at    TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                FOREIGN KEY (school_id) REFERENCES schools(id)
            )
            """;

    private static final String CREATE_TABLE_BREAK = """
            CREATE TABLE IF NOT EXISTS break_config (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                school_id      INTEGER NOT NULL,
                after_period   INTEGER NOT NULL,
                break_duration INTEGER NOT NULL,
                break_type     TEXT NOT NULL DEFAULT 'SHORT',
                created_at     TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                FOREIGN KEY (school_id) REFERENCES schools(id)
            )
            """;

    private static final String INDEX_SCHEDULE_SCHOOL = """
            CREATE INDEX IF NOT EXISTS idx_schedule_config_school ON school_schedule_config(school_id)
            """;

    private static final String INDEX_BREAK_SCHOOL = """
            CREATE INDEX IF NOT EXISTS idx_break_config_school ON break_config(school_id)
            """;

    private static final String INDEX_SCHOOL_NAME = """
            CREATE INDEX IF NOT EXISTS idx_schools_name ON schools(school_name)
            """;

    // ==================== SQL — School ====================

    private static final String INSERT_SCHOOL = """
            INSERT INTO schools (school_name, school_code) VALUES (?, ?)
            """;

    private static final String SELECT_SCHOOL_BY_ID = """
            SELECT * FROM schools WHERE id = ?
            """;

    private static final String SELECT_SCHOOL_BY_NAME = """
            SELECT * FROM schools WHERE school_name = ?
            """;

    private static final String SELECT_ALL_SCHOOLS = """
            SELECT * FROM schools ORDER BY school_name
            """;

    private static final String SELECT_DEFAULT_SCHOOL = """
            SELECT * FROM schools ORDER BY id ASC LIMIT 1
            """;

    private static final String DELETE_SCHOOL_BY_ID = """
            DELETE FROM schools WHERE id = ?
            """;

    // ==================== SQL — SchoolScheduleConfig ====================

    private static final String INSERT_SCHEDULE = """
            INSERT INTO school_schedule_config
                (school_id, period_number, start_time, end_time, duration)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SCHEDULE_BY_SCHOOL = """
            SELECT * FROM school_schedule_config WHERE school_id = ? ORDER BY period_number
            """;

    private static final String DELETE_SCHEDULE_BY_SCHOOL = """
            DELETE FROM school_schedule_config WHERE school_id = ?
            """;

    private static final String COUNT_SCHEDULE_BY_SCHOOL = """
            SELECT COUNT(*) FROM school_schedule_config WHERE school_id = ?
            """;

    // ==================== SQL — BreakConfig ====================

    private static final String INSERT_BREAK = """
            INSERT INTO break_config
                (school_id, after_period, break_duration, break_type)
            VALUES (?, ?, ?, ?)
            """;

    private static final String SELECT_BREAK_BY_SCHOOL = """
            SELECT * FROM break_config WHERE school_id = ? ORDER BY after_period
            """;

    private static final String DELETE_BREAK_BY_SCHOOL = """
            DELETE FROM break_config WHERE school_id = ?
            """;

    // ==================== 默认作息配置 ====================

    /** 默认节次时间表：第1~12节的起止时间 */
    static final String[][] DEFAULT_PERIOD_TIMES = {
            {},                          // 索引0空置（period从1开始）
            {"08:00", "08:45"},          // 第1节
            {"08:55", "09:40"},          // 第2节
            {"10:10", "10:55"},          // 第3节（大课间后）
            {"11:05", "11:50"},          // 第4节
            {"13:30", "14:15"},          // 第5节（下午）
            {"14:25", "15:10"},          // 第6节
            {"15:40", "16:25"},          // 第7节（大课间后）
            {"16:35", "17:20"},          // 第8节
            {"17:30", "18:15"},          // 第9节
            {"19:00", "19:45"},          // 第10节（晚上）
            {"19:55", "20:40"},          // 第11节
            {"20:50", "21:35"}           // 第12节
    };

    /** 默认大课间配置（哪几节之后是大课间） */
    private static final int[] DEFAULT_BREAK_AFTER = {2, 4, 6, 9};
    private static final int DEFAULT_BREAK_LONG = 20; // 大课间时长（分钟）

    @Value("${schedule.db-path:./data/claw-schedule.db}")
    private String dbPath;

    public SchoolScheduleConfigRepository() {
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SCHOOLS);
            stmt.execute(CREATE_TABLE_SCHEDULE);
            stmt.execute(CREATE_TABLE_BREAK);
            stmt.execute(INDEX_SCHEDULE_SCHOOL);
            stmt.execute(INDEX_BREAK_SCHOOL);
            try {
                stmt.execute(INDEX_SCHOOL_NAME);
            } catch (SQLException e) {
                log.debug("idx_schools_name 索引已存在");
            }
            // 自动种子默认学校
            seedDefaultSchoolIfEmpty(conn);
            log.info("学校作息配置数据库表初始化完成 | path={}", dbPath);
        } catch (SQLException e) {
            log.error("学校作息配置数据库表初始化失败 | path={}", dbPath, e);
            throw new RuntimeException("学校作息配置数据库初始化失败", e);
        }
    }

    // ==================== SchoolEntity CRUD ====================

    /**
     * 创建学校并返回含 ID 的实体
     */
    public SchoolEntity createSchool(SchoolEntity school) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SCHOOL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, school.getSchoolName());
            ps.setString(2, school.getSchoolCode());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    school.setId(rs.getLong(1));
                }
            }
            return school;
        } catch (SQLException e) {
            log.error("创建学校失败 | name={}", school.getSchoolName(), e);
            throw new RuntimeException("创建学校失败", e);
        }
    }

    /**
     * 根据 ID 查询学校
     */
    public SchoolEntity findSchoolById(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCHOOL_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSchool(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询学校失败 | id={}", id, e);
        }
        return null;
    }

    /**
     * 根据名称查询学校
     */
    public SchoolEntity findSchoolByName(String name) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCHOOL_BY_NAME)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSchool(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询学校失败 | name={}", name, e);
        }
        return null;
    }

    /**
     * 获取所有学校列表
     */
    public List<SchoolEntity> findAllSchools() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL_SCHOOLS)) {
            List<SchoolEntity> schools = new ArrayList<>();
            while (rs.next()) {
                schools.add(mapSchool(rs));
            }
            return schools;
        } catch (SQLException e) {
            log.error("查询所有学校失败", e);
            return List.of();
        }
    }

    /**
     * 获取第一个学校（默认学校）
     */
    public SchoolEntity findDefaultSchool() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_DEFAULT_SCHOOL)) {
            if (rs.next()) {
                return mapSchool(rs);
            }
        } catch (SQLException e) {
            log.error("查询默认学校失败", e);
        }
        return null;
    }

    /**
     * 删除学校（同时级联删除其作息和课间配置）
     */
    public void deleteSchool(Long schoolId) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(DELETE_SCHEDULE_BY_SCHOOL)) {
                    ps.setLong(1, schoolId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BREAK_BY_SCHOOL)) {
                    ps.setLong(1, schoolId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(DELETE_SCHOOL_BY_ID)) {
                    ps.setLong(1, schoolId);
                    ps.executeUpdate();
                }
                conn.commit();
                log.info("学校已删除 | schoolId={}", schoolId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("删除学校失败 | schoolId={}", schoolId, e);
        }
    }

    // ==================== SchoolScheduleConfig CRUD ====================

    /**
     * 插入一条节次配置
     */
    public void insertSchedule(Connection conn, SchoolScheduleConfig config) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SCHEDULE, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, config.getSchoolId());
            ps.setInt(2, config.getPeriodNumber());
            ps.setString(3, config.getStartTime());
            ps.setString(4, config.getEndTime());
            if (config.getDuration() != null) {
                ps.setInt(5, config.getDuration());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    config.setId(rs.getLong(1));
                }
            }
        }
    }

    /**
     * 查询学校的所有节次配置
     */
    public List<SchoolScheduleConfig> findBySchoolId(Long schoolId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCHEDULE_BY_SCHOOL)) {
            ps.setLong(1, schoolId);
            List<SchoolScheduleConfig> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapSchedule(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询学校作息配置失败 | schoolId={}", schoolId, e);
            return List.of();
        }
    }

    /**
     * 统计学校是否有作息配置
     */
    public boolean hasScheduleConfig(Long schoolId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_SCHEDULE_BY_SCHOOL)) {
            ps.setLong(1, schoolId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("统计学校作息配置失败 | schoolId={}", schoolId, e);
            return false;
        }
    }

    /**
     * 覆盖保存学校的全部节次配置（先删除旧数据，再批量插入）
     */
    public List<SchoolScheduleConfig> replaceAllSchedule(Long schoolId, List<SchoolScheduleConfig> configs) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(DELETE_SCHEDULE_BY_SCHOOL)) {
                    ps.setLong(1, schoolId);
                    ps.executeUpdate();
                }
                for (SchoolScheduleConfig config : configs) {
                    config.setSchoolId(schoolId);
                    insertSchedule(conn, config);
                }
                conn.commit();
                log.info("学校作息配置保存完成 | schoolId={} | count={}", schoolId, configs.size());
                return findBySchoolId(schoolId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("学校作息配置保存失败 | schoolId={}", schoolId, e);
            throw new RuntimeException("学校作息配置保存失败", e);
        }
    }

    /**
     * 初始化学校默认作息配置（写入节次时间 + 课间配置）
     */
    public void initSchoolSchedule(Long schoolId) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 插入默认节次时间
                for (int p = 1; p < DEFAULT_PERIOD_TIMES.length; p++) {
                    SchoolScheduleConfig config = new SchoolScheduleConfig(
                            schoolId, p,
                            DEFAULT_PERIOD_TIMES[p][0],
                            DEFAULT_PERIOD_TIMES[p][1],
                            null
                    );
                    insertSchedule(conn, config);
                }

                // 插入默认课间配置
                for (int after : DEFAULT_BREAK_AFTER) {
                    BreakConfig breakCfg = new BreakConfig(
                            schoolId, after, DEFAULT_BREAK_LONG, BreakConfig.BREAK_LONG
                    );
                    insertBreak(conn, breakCfg);
                }

                conn.commit();
                log.info("学校默认作息配置已初始化 | schoolId={}", schoolId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("学校默认作息配置初始化失败 | schoolId={}", schoolId, e);
        }
    }

    // ==================== BreakConfig CRUD ====================

    /**
     * 插入一条课间配置
     */
    public void insertBreak(Connection conn, BreakConfig config) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_BREAK, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, config.getSchoolId());
            ps.setInt(2, config.getAfterPeriod());
            ps.setInt(3, config.getBreakDuration());
            ps.setString(4, config.getBreakType());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    config.setId(rs.getLong(1));
                }
            }
        }
    }

    /**
     * 查询学校的所有课间配置
     */
    public List<BreakConfig> findBreaksBySchoolId(Long schoolId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BREAK_BY_SCHOOL)) {
            ps.setLong(1, schoolId);
            List<BreakConfig> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapBreak(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询学校课间配置失败 | schoolId={}", schoolId, e);
            return List.of();
        }
    }

    /**
     * 覆盖保存学校的全部课间配置
     */
    public List<BreakConfig> replaceAllBreaks(Long schoolId, List<BreakConfig> breaks) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BREAK_BY_SCHOOL)) {
                    ps.setLong(1, schoolId);
                    ps.executeUpdate();
                }
                for (BreakConfig b : breaks) {
                    b.setSchoolId(schoolId);
                    insertBreak(conn, b);
                }
                conn.commit();
                log.info("学校课间配置保存完成 | schoolId={} | count={}", schoolId, breaks.size());
                return findBreaksBySchoolId(schoolId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("学校课间配置保存失败 | schoolId={}", schoolId, e);
            throw new RuntimeException("学校课间配置保存失败", e);
        }
    }

    // ==================== 默认学校种子 ====================

    /**
     * 如果学校表为空，自动创建默认学校及其作息配置
     */
    private void seedDefaultSchoolIfEmpty(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM schools")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return; // 已有学校数据
            }
        }

        // 创建默认学校
        SchoolEntity defaultSchool = new SchoolEntity("Default University", "DEFAULT");
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SCHOOL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, defaultSchool.getSchoolName());
            ps.setString(2, defaultSchool.getSchoolCode());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    defaultSchool.setId(rs.getLong(1));
                }
            }
        }

        // 初始化默认作息
        for (int p = 1; p < DEFAULT_PERIOD_TIMES.length; p++) {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SCHEDULE)) {
                ps.setLong(1, defaultSchool.getId());
                ps.setInt(2, p);
                ps.setString(3, DEFAULT_PERIOD_TIMES[p][0]);
                ps.setString(4, DEFAULT_PERIOD_TIMES[p][1]);
                ps.setNull(5, Types.INTEGER);
                ps.executeUpdate();
            }
        }

        // 默认课间
        for (int after : DEFAULT_BREAK_AFTER) {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_BREAK)) {
                ps.setLong(1, defaultSchool.getId());
                ps.setInt(2, after);
                ps.setInt(3, DEFAULT_BREAK_LONG);
                ps.setString(4, BreakConfig.BREAK_LONG);
                ps.executeUpdate();
            }
        }

        log.info("默认学校已创建 | id={} | name={}", defaultSchool.getId(), defaultSchool.getSchoolName());
    }

    // ==================== 内部方法 ====================

    private SchoolEntity mapSchool(ResultSet rs) throws SQLException {
        SchoolEntity school = new SchoolEntity();
        school.setId(rs.getLong("id"));
        school.setSchoolName(rs.getString("school_name"));
        school.setSchoolCode(rs.getString("school_code"));
        school.setCreatedAt(rs.getString("created_at"));
        return school;
    }

    private SchoolScheduleConfig mapSchedule(ResultSet rs) throws SQLException {
        SchoolScheduleConfig config = new SchoolScheduleConfig();
        config.setId(rs.getLong("id"));
        config.setSchoolId(rs.getLong("school_id"));
        config.setPeriodNumber(rs.getInt("period_number"));
        config.setStartTime(rs.getString("start_time"));
        config.setEndTime(rs.getString("end_time"));
        long duration = rs.getLong("duration");
        if (!rs.wasNull()) {
            config.setDuration((int) duration);
        }
        return config;
    }

    private BreakConfig mapBreak(ResultSet rs) throws SQLException {
        BreakConfig config = new BreakConfig();
        config.setId(rs.getLong("id"));
        config.setSchoolId(rs.getLong("school_id"));
        config.setAfterPeriod(rs.getInt("after_period"));
        config.setBreakDuration(rs.getInt("break_duration"));
        config.setBreakType(rs.getString("break_type"));
        return config;
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
            parentDir.mkdirs();
        }
    }
}