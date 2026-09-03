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

@Repository
public class ExamRepository {
    private static final Logger log = LoggerFactory.getLogger(ExamRepository.class);
    private static final String TABLE_NAME = "exam_schedule";
    private static final String TABLE_DDL = "CREATE TABLE IF NOT EXISTS exam_schedule ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "user_id TEXT NOT NULL, "
            + "course_name TEXT NOT NULL, "
            + "exam_date TEXT NOT NULL, "
            + "start_time TEXT NOT NULL DEFAULT '', "
            + "end_time TEXT NOT NULL DEFAULT '', "
            + "location TEXT NOT NULL DEFAULT '', "
            + "seat_number TEXT NOT NULL DEFAULT '', "
            + "exam_type TEXT NOT NULL DEFAULT 'FINAL', "
            + "notes TEXT NOT NULL DEFAULT '', "
            + "created_time TEXT NOT NULL DEFAULT (datetime('now','localtime')))";
    private static final String INDEX_USER = "CREATE INDEX IF NOT EXISTS idx_exam_schedule_user ON exam_schedule(user_id)";
    private static final String INSERT_SQL = "INSERT INTO exam_schedule (user_id, course_name, exam_date, start_time, end_time, location, seat_number, exam_type, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_USER = "SELECT * FROM exam_schedule WHERE user_id = ? ORDER BY exam_date, start_time";
    private static final String SELECT_UPCOMING = "SELECT * FROM exam_schedule WHERE user_id = ? AND exam_date >= ? ORDER BY exam_date, start_time";
    private static final String SELECT_BY_DATE = "SELECT * FROM exam_schedule WHERE user_id = ? AND exam_date = ? ORDER BY start_time";
    private static final String DELETE_BY_USER = "DELETE FROM exam_schedule WHERE user_id = ?";
    private static final String DELETE_BY_ID = "DELETE FROM exam_schedule WHERE id = ? AND user_id = ?";
    private static final String UPDATE_SQL = "UPDATE exam_schedule SET course_name=?, exam_date=?, start_time=?, end_time=?, location=?, seat_number=?, exam_type=?, notes=? WHERE id=? AND user_id=?";
    private static final String SELECT_BY_ID = "SELECT * FROM exam_schedule WHERE id = ?";
    private static final String COUNT_BY_USER = "SELECT COUNT(*) FROM exam_schedule WHERE user_id = ?";
    private static final String SELECT_ALL = "SELECT * FROM exam_schedule";

    @Value("${exam.db-path:./data/claw-exam.db}")
    private String dbPath;

    @PostConstruct
    public void init() {
        ensureDir();
        try (Connection conn = getConn(); Statement s = conn.createStatement()) {
            s.execute(TABLE_DDL); s.execute(INDEX_USER);
            log.info("考试数据库表初始化完成 | table={} | path={}", TABLE_NAME, dbPath);
        } catch (SQLException e) { throw new RuntimeException("考试数据库初始化失败", e); }
    }

    public List<ExamEntity> replaceAll(String userId, List<ExamEntity> exams) {
        try (Connection conn = getConn()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_USER)) { ps.setString(1, userId); ps.executeUpdate(); }
                for (ExamEntity e : exams) { e.setUserId(userId); insert(conn, e); }
                conn.commit();
                return findByUserId(userId);
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("考试导入失败", e); }
    }

    private void insert(Connection conn, ExamEntity e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.getUserId()); ps.setString(2, e.getCourseName());
            ps.setString(3, e.getExamDate()); ps.setString(4, e.getStartTime());
            ps.setString(5, e.getEndTime()); ps.setString(6, e.getLocation());
            ps.setString(7, e.getSeatNumber()); ps.setString(8, e.getExamType());
            ps.setString(9, e.getNotes()); ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) e.setId(rs.getLong(1)); }
        }
    }

    public List<ExamEntity> findByUserId(String userId) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(SELECT_BY_USER)) { p.setString(1, userId); return mapAll(p); }
        catch (SQLException ex) { return List.of(); }
    }

    public List<ExamEntity> findUpcoming(String userId, String fromDate) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(SELECT_UPCOMING)) { p.setString(1, userId); p.setString(2, fromDate); return mapAll(p); }
        catch (SQLException ex) { return List.of(); }
    }

    public List<ExamEntity> findByDate(String userId, String examDate) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(SELECT_BY_DATE)) { p.setString(1, userId); p.setString(2, examDate); return mapAll(p); }
        catch (SQLException ex) { return List.of(); }
    }

    public List<ExamEntity> findAll() {
        try (Connection c = getConn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(SELECT_ALL)) { return mapAllFromRs(r); }
        catch (SQLException ex) { return List.of(); }
    }

    public int countByUserId(String userId) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(COUNT_BY_USER)) { p.setString(1, userId); try (ResultSet r = p.executeQuery()) { if (r.next()) return r.getInt(1); } }
        catch (SQLException ex) { } return 0;
    }

    public ExamEntity findById(Long id) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(SELECT_BY_ID)) { p.setLong(1, id); try (ResultSet r = p.executeQuery()) { if (r.next()) return mapOne(r); } }
        catch (SQLException ex) { } return null;
    }

    public boolean update(ExamEntity e) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(UPDATE_SQL)) {
            p.setString(1, e.getCourseName()); p.setString(2, e.getExamDate());
            p.setString(3, e.getStartTime()); p.setString(4, e.getEndTime());
            p.setString(5, e.getLocation()); p.setString(6, e.getSeatNumber());
            p.setString(7, e.getExamType()); p.setString(8, e.getNotes());
            p.setLong(9, e.getId()); p.setString(10, e.getUserId());
            return p.executeUpdate() > 0;
        } catch (SQLException ex) { return false; }
    }

    public void deleteByUserId(String userId) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(DELETE_BY_USER)) { p.setString(1, userId); p.executeUpdate(); }
        catch (SQLException ex) { }
    }

    public boolean deleteById(Long id, String userId) {
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(DELETE_BY_ID)) { p.setLong(1, id); p.setString(2, userId); return p.executeUpdate() > 0; }
        catch (SQLException ex) { return false; }
    }

    private List<ExamEntity> mapAll(PreparedStatement p) throws SQLException { List<ExamEntity> list = new ArrayList<>(); try (ResultSet r = p.executeQuery()) { while (r.next()) list.add(mapOne(r)); } return list; }
    private List<ExamEntity> mapAllFromRs(ResultSet r) throws SQLException { List<ExamEntity> list = new ArrayList<>(); while (r.next()) list.add(mapOne(r)); return list; }
    private ExamEntity mapOne(ResultSet r) throws SQLException {
        ExamEntity e = new ExamEntity();
        e.setId(r.getLong("id")); e.setUserId(r.getString("user_id"));
        e.setCourseName(r.getString("course_name")); e.setExamDate(r.getString("exam_date"));
        e.setStartTime(r.getString("start_time")); e.setEndTime(r.getString("end_time"));
        e.setLocation(r.getString("location")); e.setSeatNumber(r.getString("seat_number"));
        e.setExamType(r.getString("exam_type")); e.setNotes(r.getString("notes"));
        e.setCreatedTime(r.getString("created_time"));
        return e;
    }
    private Connection getConn() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA journal_mode=WAL"); }
        return c;
    }
    private void ensureDir() { File f = new File(dbPath); File p = f.getParentFile(); if (p != null && !p.exists()) p.mkdirs(); }
}