package com.youkeda.exercise.claw.feature.schedule;

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
 * 课表 SQLite 持久化仓库
 *
 * <p>独立数据库文件（{@code claw-schedule.db}），纯 JDBC + WAL 模式。
 * 表名 {@code course_schedule}，以 {@code userId} 作为隔离键，
 * 不同用户的课程数据完全隔离。
 */
@Repository
public class CourseRepository {

    private static final Logger log = LoggerFactory.getLogger(CourseRepository.class);

    private static final String TABLE_NAME = "course_schedule";

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS course_schedule (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id      TEXT NOT NULL,
                course_name  TEXT NOT NULL,
                teacher      TEXT NOT NULL DEFAULT '',
                day_of_week  INTEGER NOT NULL,
                start_period INTEGER NOT NULL,
                end_period   INTEGER NOT NULL,
                classroom    TEXT NOT NULL DEFAULT '',
                start_week   INTEGER NOT NULL DEFAULT 1,
                end_week     INTEGER NOT NULL DEFAULT 20,
                week_type    TEXT NOT NULL DEFAULT 'ALL',
                semester_id  INTEGER,
                created_time TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """;

    /** 旧数据库迁移：新增 semester_id 列（兼容已有数据库） */
    private static final String MIGRATE_ADD_SEMESTER_ID = """
            ALTER TABLE course_schedule ADD COLUMN semester_id INTEGER
            """;

    private static final String INDEX_USER = """
            CREATE INDEX IF NOT EXISTS idx_course_schedule_user ON course_schedule(user_id)
            """;

    /** 学期课程查询索引 */
    private static final String INDEX_USER_SEMESTER = """
            CREATE INDEX IF NOT EXISTS idx_course_user_semester ON course_schedule(user_id, semester_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO course_schedule
                (user_id, course_name, teacher, day_of_week,
                 start_period, end_period, classroom,
                 start_week, end_week, week_type, semester_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_USER = """
            SELECT * FROM course_schedule WHERE user_id = ? ORDER BY day_of_week, start_period
            """;

    private static final String SELECT_BY_USER_AND_DAY = """
            SELECT * FROM course_schedule WHERE user_id = ? AND day_of_week = ?
            ORDER BY start_period
            """;

    private static final String SELECT_BY_USER_AND_SEMESTER = """
            SELECT * FROM course_schedule WHERE user_id = ? AND semester_id = ?
            ORDER BY day_of_week, start_period
            """;

    private static final String DELETE_BY_USER = """
            DELETE FROM course_schedule WHERE user_id = ?
            """;

    private static final String DELETE_BY_USER_AND_SEMESTER = """
            DELETE FROM course_schedule WHERE user_id = ? AND semester_id = ?
            """;

    private static final String DELETE_BY_USER_NULL_SEMESTER = """
            DELETE FROM course_schedule WHERE user_id = ? AND semester_id IS NULL
            """;

    private static final String SELECT_BY_USER_NULL_SEMESTER = """
            SELECT * FROM course_schedule WHERE user_id = ? AND semester_id IS NULL
            ORDER BY day_of_week, start_period
            """;

    private static final String DELETE_BY_ID = """
            DELETE FROM course_schedule WHERE id = ? AND user_id = ?
            """;

    private static final String UPDATE_SQL = """
            UPDATE course_schedule SET
                course_name = ?, teacher = ?, day_of_week = ?,
                start_period = ?, end_period = ?, classroom = ?,
                start_week = ?, end_week = ?, week_type = ?,
                semester_id = ?
            WHERE id = ? AND user_id = ?
            """;

    private static final String SELECT_BY_ID = """
            SELECT * FROM course_schedule WHERE id = ?
            """;

    private static final String COUNT_BY_USER = """
            SELECT COUNT(*) FROM course_schedule WHERE user_id = ?
            """;

    private static final String SELECT_ALL = """
            SELECT * FROM course_schedule
            """;

    @Value("${schedule.db-path:./data/claw-schedule.db}")
    private String dbPath;

    public CourseRepository() {
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(TABLE_DDL);
            stmt.execute(INDEX_USER);
            // 学期查询索引
            try {
                stmt.execute(INDEX_USER_SEMESTER);
            } catch (SQLException e) {
                log.debug("idx_course_user_semester 索引已存在，跳过创建");
            }
            // 迁移：为已有数据库添加 semester_id 列（字段已存在时忽略）
            try {
                stmt.execute(MIGRATE_ADD_SEMESTER_ID);
                log.info("数据库迁移完成：已添加 semester_id 列 | table={}", TABLE_NAME);
            } catch (SQLException e) {
                // 列已存在时忽略（SQLite 不支持 IF NOT EXISTS）
                log.debug("semester_id 列已存在，跳过迁移 | table={}", TABLE_NAME);
            }
            log.info("课表数据库表初始化完成 | table={} | path={}", TABLE_NAME, dbPath);
        } catch (SQLException e) {
            log.error("课表数据库表初始化失败 | path={}", dbPath, e);
            throw new RuntimeException("课表数据库初始化失败", e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 批量保存课程（先删除该用户旧数据再插入，实现覆盖导入）
     *
     * @param userId  用户标识（隔离键）
     * @param courses 课程列表
     * @return 保存后的课程列表（含 ID）
     */
    public List<CourseEntity> replaceAll(String userId, List<CourseEntity> courses) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 删除该用户旧数据
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER)) {
                    ps.setString(1, userId);
                    int deleted = ps.executeUpdate();
                    log.debug("已删除用户旧课程 | userId={} | count={}", userId, deleted);
                }

                // 批量插入
                for (CourseEntity course : courses) {
                    course.setUserId(userId);
                    insert(conn, course);
                }

                conn.commit();
                log.info("课表导入完成 | userId={} | courses={}", userId, courses.size());
                return findByUserId(userId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("课表导入失败 | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("课表导入失败", e);
        }
    }

    /**
     * 无学期课表覆盖导入：只删除该用户 {@code semester_id IS NULL} 的课程，保留学期绑定课程。
     *
     * <p>与 {@link #replaceAll} 的区别：不误删其它学期的数据（单用户可同时维护多个学期课表）。
     *
     * @param userId  用户标识（隔离键）
     * @param courses 无学期课程列表（插入时强制 {@code semester_id = null}）
     * @return 保存后的无学期课程列表（含 ID）
     */
    public List<CourseEntity> replaceAllNullSemester(String userId, List<CourseEntity> courses) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 只删除该用户无学期的旧课程
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER_NULL_SEMESTER)) {
                    ps.setString(1, userId);
                    int deleted = ps.executeUpdate();
                    log.debug("已删除用户无学期旧课程 | userId={} | count={}", userId, deleted);
                }

                // 批量插入（强制 semester_id = null）
                for (CourseEntity course : courses) {
                    course.setUserId(userId);
                    course.setSemesterId(null);
                    insert(conn, course);
                }

                conn.commit();
                log.info("无学期课表导入完成 | userId={} | courses={}", userId, courses.size());
                return findByUserIdNullSemester(userId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("无学期课表导入失败 | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("无学期课表导入失败", e);
        }
    }

    /**
     * 插入单条课程（自动生成 ID）
     */
    private void insert(Connection conn, CourseEntity course) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, course.getUserId());
            ps.setString(2, course.getCourseName());
            ps.setString(3, course.getTeacher());
            ps.setInt(4, course.getDayOfWeek());
            ps.setInt(5, course.getStartPeriod());
            ps.setInt(6, course.getEndPeriod());
            ps.setString(7, course.getClassroom());
            ps.setInt(8, course.getStartWeek());
            ps.setInt(9, course.getEndWeek());
            ps.setString(10, course.getWeekType());
            if (course.getSemesterId() != null) {
                ps.setLong(11, course.getSemesterId());
            } else {
                ps.setNull(11, Types.INTEGER);
            }
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    course.setId(rs.getLong(1));
                }
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 查询用户全部课程（按星期和节次排序）
     *
     * @param userId 用户标识
     * @return 课程列表
     */
    public List<CourseEntity> findByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER)) {
            ps.setString(1, userId);
            List<CourseEntity> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapCourse(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户课程失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 查询用户无学期课程（semester_id IS NULL，用于覆盖导入后返回）
     *
     * @param userId 用户标识
     */
    private List<CourseEntity> findByUserIdNullSemester(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_NULL_SEMESTER)) {
            ps.setString(1, userId);
            List<CourseEntity> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapCourse(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户无学期课程失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 查询用户某天的课程
     *
     * @param userId    用户标识
     * @param dayOfWeek 星期几（1=周一 ~ 7=周日）
     */
    public List<CourseEntity> findByUserIdAndDay(String userId, int dayOfWeek) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_AND_DAY)) {
            ps.setString(1, userId);
            ps.setInt(2, dayOfWeek);
            List<CourseEntity> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapCourse(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("查询用户课程失败 | userId={} | day={}", userId, dayOfWeek, e);
            return List.of();
        }
    }

    /**
     * 查询所有用户的课程（用于提醒调度器全量扫描）
     */
    public List<CourseEntity> findAll() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_ALL)) {
            List<CourseEntity> results = new ArrayList<>();
            while (rs.next()) {
                results.add(mapCourse(rs));
            }
            return results;
        } catch (SQLException e) {
            log.error("查询全部课程失败", e);
            return List.of();
        }
    }

    /**
     * 按学期查询用户课程
     *
     * @param userId     用户标识
     * @param semesterId 学期 ID
     * @return 该学期的课程列表
     */
    public List<CourseEntity> findByUserIdAndSemester(String userId, Long semesterId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_AND_SEMESTER)) {
            ps.setString(1, userId);
            ps.setLong(2, semesterId);
            List<CourseEntity> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapCourse(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.error("按学期查询课程失败 | userId={} | semesterId={}", userId, semesterId, e);
            return List.of();
        }
    }

    /**
     * 统计用户课程数量
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
            log.error("统计课程数量失败 | userId={}", userId, e);
        }
        return 0;
    }

    /**
     * 根据 ID 查询课程
     */
    public CourseEntity findById(Long id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapCourse(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询课程失败 | id={}", id, e);
        }
        return null;
    }

    // ==================== 更新 ====================

    /**
     * 更新课程信息（含 userId 归属校验）
     *
     * @param course 课程实体（必须包含 id 和 userId）
     * @return 是否更新成功
     */
    public boolean update(CourseEntity course) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_SQL)) {
            ps.setString(1, course.getCourseName());
            ps.setString(2, course.getTeacher());
            ps.setInt(3, course.getDayOfWeek());
            ps.setInt(4, course.getStartPeriod());
            ps.setInt(5, course.getEndPeriod());
            ps.setString(6, course.getClassroom());
            ps.setInt(7, course.getStartWeek());
            ps.setInt(8, course.getEndWeek());
            ps.setString(9, course.getWeekType());
            if (course.getSemesterId() != null) {
                ps.setLong(10, course.getSemesterId());
            } else {
                ps.setNull(10, Types.INTEGER);
            }
            ps.setLong(11, course.getId());
            ps.setString(12, course.getUserId());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("课程已更新 | id={} | userId={} | name={}",
                        course.getId(), course.getUserId(), course.getCourseName());
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("更新课程失败 | id={} | error={}", course.getId(), e.getMessage(), e);
            return false;
        }
    }

    // ==================== 删除 ====================

    /**
     * 删除用户全部课程（按 userId 隔离）
     */
    public void deleteByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER)) {
            ps.setString(1, userId);
            int count = ps.executeUpdate();
            log.info("已删除用户课程 | userId={} | count={}", userId, count);
        } catch (SQLException e) {
            log.error("删除用户课程失败 | userId={}", userId, e);
        }
    }

    /**
     * 删除单条课程（含 userId 归属校验）
     */
    public boolean deleteById(Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_ID)) {
            ps.setLong(1, id);
            ps.setString(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("删除课程失败 | id={} | userId={}", id, userId, e);
            return false;
        }
    }

    /**
     * 删除用户特定学期全部课程（按 userId + semesterId 隔离）
     *
     * @param userId     用户标识
     * @param semesterId 学期 ID
     * @return 删除数量
     */
    public int deleteByUserIdAndSemester(String userId, Long semesterId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER_AND_SEMESTER)) {
            ps.setString(1, userId);
            ps.setLong(2, semesterId);
            int count = ps.executeUpdate();
            log.info("已删除用户学期课程 | userId={} | semesterId={} | count={}", userId, semesterId, count);
            return count;
        } catch (SQLException e) {
            log.error("删除用户学期课程失败 | userId={} | semesterId={}", userId, semesterId, e);
            return 0;
        }
    }

    /**
     * 按学期覆盖保存课程（先删除该用户该学期旧数据，再批量插入）
     *
     * <p>只影响指定学期内的课程，不影响该用户其他学期的数据。
     *
     * @param userId     用户标识
     * @param semesterId 学期 ID
     * @param courses    课程列表
     * @return 保存后的课程列表（含 ID）
     */
    public List<CourseEntity> replaceAllBySemester(String userId, Long semesterId, List<CourseEntity> courses) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 删除该用户该学期旧数据
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER_AND_SEMESTER)) {
                    ps.setString(1, userId);
                    ps.setLong(2, semesterId);
                    int deleted = ps.executeUpdate();
                    log.debug("已删除用户学期旧课程 | userId={} | semesterId={} | count={}", userId, semesterId, deleted);
                }

                // 批量插入（绑定 semesterId）
                for (CourseEntity course : courses) {
                    course.setUserId(userId);
                    course.setSemesterId(semesterId);
                    insert(conn, course);
                }

                conn.commit();
                log.info("课表按学期导入完成 | userId={} | semesterId={} | courses={}", userId, semesterId, courses.size());
                return findByUserIdAndSemester(userId, semesterId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("课表按学期导入失败 | userId={} | semesterId={} | error={}", userId, semesterId, e.getMessage(), e);
            throw new RuntimeException("课表按学期导入失败", e);
        }
    }

    // ==================== 内部方法 ====================

    private CourseEntity mapCourse(ResultSet rs) throws SQLException {
        CourseEntity c = new CourseEntity();
        c.setId(rs.getLong("id"));
        c.setUserId(rs.getString("user_id"));
        c.setCourseName(rs.getString("course_name"));
        c.setTeacher(rs.getString("teacher"));
        c.setDayOfWeek(rs.getInt("day_of_week"));
        c.setStartPeriod(rs.getInt("start_period"));
        c.setEndPeriod(rs.getInt("end_period"));
        c.setClassroom(rs.getString("classroom"));
        c.setStartWeek(rs.getInt("start_week"));
        c.setEndWeek(rs.getInt("end_week"));
        c.setWeekType(rs.getString("week_type"));
        long semesterId = rs.getLong("semester_id");
        if (!rs.wasNull()) {
            c.setSemesterId(semesterId);
        }
        return c;
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