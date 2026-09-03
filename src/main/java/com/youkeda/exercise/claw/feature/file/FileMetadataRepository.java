package com.youkeda.exercise.claw.feature.file;

import com.youkeda.exercise.claw.domain.file.FileMetadata;
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
 * 文件元数据 SQLite 持久化仓库
 *
 * <p>管理 {@code file_metadata} 表，与 {@link com.youkeda.exercise.claw.feature.schedule.CourseRepository}
 * 使用独立数据库文件 {@code claw-files.db}。
 *
 * <p>所有查询方法强制要求 {@code userId} 参数，确保用户隔离。
 */
@Repository
public class FileMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(FileMetadataRepository.class);

    private static final String TABLE_NAME = "file_metadata";

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS file_metadata (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id         TEXT NOT NULL,
                filename        TEXT NOT NULL,
                stored_name     TEXT NOT NULL,
                file_type       TEXT NOT NULL DEFAULT '',
                size            INTEGER NOT NULL DEFAULT 0,
                category        TEXT DEFAULT '',
                extracted_text  TEXT DEFAULT '',
                summary         TEXT DEFAULT '',
                source          TEXT DEFAULT '',
                status          TEXT NOT NULL DEFAULT 'active',
                created_time    TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """;

    /** 旧数据库迁移：新增 source 列 */
    private static final String MIGRATE_ADD_SOURCE = """
            ALTER TABLE file_metadata ADD COLUMN source TEXT DEFAULT ''
            """;

    private static final String INDEX_USER = """
            CREATE INDEX IF NOT EXISTS idx_file_metadata_user ON file_metadata(user_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO file_metadata
                (user_id, filename, stored_name, file_type, size,
                 category, extracted_text, summary, source, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_USER = """
            SELECT * FROM file_metadata WHERE user_id = ? AND status = 'active'
            ORDER BY created_time DESC
            """;

    private static final String SELECT_BY_USER_PAGINATED = """
            SELECT * FROM file_metadata WHERE user_id = ? AND status = 'active'
            ORDER BY created_time DESC LIMIT ? OFFSET ?
            """;

    private static final String COUNT_ACTIVE_BY_USER = """
            SELECT COUNT(*) FROM file_metadata WHERE user_id = ? AND status = 'active'
            """;

    private static final String SELECT_BY_ID_AND_USER = """
            SELECT * FROM file_metadata WHERE id = ? AND user_id = ? AND status = 'active'
            """;

    private static final String SEARCH_BY_KEYWORD = """
            SELECT * FROM file_metadata
            WHERE user_id = ? AND status = 'active'
            AND (filename LIKE ? OR category LIKE ?)
            ORDER BY created_time DESC
            """;

    private static final String SEARCH_BY_KEYWORD_AND_TYPE = """
            SELECT * FROM file_metadata
            WHERE user_id = ? AND status = 'active' AND file_type = ?
            AND (filename LIKE ? OR category LIKE ?)
            ORDER BY created_time DESC
            """;

    private static final String UPDATE_FILENAME = """
            UPDATE file_metadata SET filename = ? WHERE id = ? AND user_id = ?
            """;

    private static final String UPDATE_SOURCE = """
            UPDATE file_metadata SET source = ? WHERE id = ? AND user_id = ?
            """;

    private static final String SOFT_DELETE_BY_ID_AND_USER = """
            UPDATE file_metadata SET status = 'deleted' WHERE id = ? AND user_id = ?
            """;

    @Value("${file.db-path:./data/claw-files.db}")
    private String dbPath;

    public FileMetadataRepository() {
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(TABLE_DDL);
            // 迁移：为已有数据库添加 source 列
            try {
                stmt.execute(MIGRATE_ADD_SOURCE);
                log.info("数据库迁移完成：已添加 source 列 | table={}", TABLE_NAME);
            } catch (SQLException e) {
                log.debug("source 列已存在，跳过迁移 | table={}", TABLE_NAME);
            }
            try {
                stmt.execute(INDEX_USER);
            } catch (SQLException e) {
                log.debug("idx_file_metadata_user 索引已存在，跳过创建");
            }
            log.info("文件元数据表初始化完成 | table={} | path={}", TABLE_NAME, dbPath);
        } catch (SQLException e) {
            log.error("文件元数据表初始化失败 | path={}", dbPath, e);
            throw new RuntimeException("文件元数据表初始化失败", e);
        }
    }

    // ==================== 写入 ====================

    /**
     * 插入文件元数据（自动生成 ID）
     */
    public void insert(FileMetadata metadata) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, metadata.getUserId());
            ps.setString(2, metadata.getFilename());
            ps.setString(3, metadata.getStoredName());
            ps.setString(4, metadata.getFileType());
            ps.setLong(5, metadata.getSize());
            ps.setString(6, metadata.getCategory() != null ? metadata.getCategory() : "");
            ps.setString(7, metadata.getExtractedText() != null ? metadata.getExtractedText() : "");
            ps.setString(8, metadata.getSummary() != null ? metadata.getSummary() : "");
            ps.setString(9, metadata.getSource() != null ? metadata.getSource() : "");
            ps.setString(10, "active");
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    metadata.setId(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.error("文件元数据插入失败 | userId={} | filename={}", metadata.getUserId(), metadata.getFilename(), e);
            throw new RuntimeException("文件元数据插入失败", e);
        }
    }

    // ==================== 查询（强制 userId） ====================

    /**
     * 查询用户的所有文件列表（无分页）
     *
     * @param userId 用户标识（必填）
     */
    public List<FileMetadata> findByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER)) {
            ps.setString(1, userId);
            return mapResults(ps);
        } catch (SQLException e) {
            log.error("查询用户文件列表失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 分页查询用户文件列表
     *
     * @param userId 用户标识（必填）
     * @param limit  每页数量
     * @param offset 偏移量
     */
    public List<FileMetadata> findByUserIdPaginated(String userId, int limit, int offset) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_PAGINATED)) {
            ps.setString(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResults(ps);
        } catch (SQLException e) {
            log.error("分页查询用户文件列表失败 | userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 统计用户活跃文件总数
     *
     * @param userId 用户标识
     */
    public int countActiveByUserId(String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_ACTIVE_BY_USER)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("统计用户文件总数失败 | userId={}", userId, e);
        }
        return 0;
    }

    /**
     * 按 ID 和 userId 查询单个文件（强制 userId 校验）
     *
     * @param id     文件 ID
     * @param userId 用户标识（必填）
     * @return 文件元数据，不存在返回 null
     */
    public FileMetadata findByIdAndUserId(Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID_AND_USER)) {
            ps.setLong(1, id);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapMetadata(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询文件元数据失败 | id={} | userId={}", id, userId, e);
        }
        return null;
    }

    /**
     * 按关键词搜索用户文件（模糊匹配）
     *
     * @param userId   用户标识（必填）
     * @param keyword  搜索关键词
     * @param fileType 按文件类型过滤（可选，传 null 不过滤）
     * @param limit    返回数量限制
     */
    public List<FileMetadata> search(String userId, String keyword, String fileType, int limit) {
        String likePattern = "%" + keyword + "%";
        String sql = fileType != null && !fileType.isBlank()
                ? SEARCH_BY_KEYWORD_AND_TYPE : SEARCH_BY_KEYWORD;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, userId);
            if (fileType != null && !fileType.isBlank()) {
                ps.setString(idx++, fileType);
            }
            ps.setString(idx++, likePattern);
            ps.setString(idx, likePattern);

            List<FileMetadata> results = mapResults(ps);
            return results.size() > limit ? results.subList(0, limit) : results;
        } catch (SQLException e) {
            log.error("搜索文件失败 | userId={} | keyword={}", userId, keyword, e);
            return List.of();
        }
    }

    // ==================== 更新 ====================

    /**
     * 更新文件名（仅允许修改显示名，不影响存储文件）
     *
     * @param newFilename 新文件名
     * @param id          文件 ID
     * @param userId      用户标识（必填，校验归属）
     * @return true 表示已更新
     */
    public boolean updateFilename(String newFilename, Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_FILENAME)) {
            ps.setString(1, newFilename);
            ps.setLong(2, id);
            ps.setString(3, userId);
            boolean updated = ps.executeUpdate() > 0;
            if (updated) {
                log.info("文件名已更新 | id={} | userId={} | newFilename={}", id, userId, newFilename);
            }
            return updated;
        } catch (SQLException e) {
            log.error("文件名更新失败 | id={} | userId={}", id, userId, e);
            return false;
        }
    }

    /**
     * 更新文件来源标记
     */
    public boolean updateSource(String source, Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(UPDATE_SOURCE)) {
            ps.setString(1, source);
            ps.setLong(2, id);
            ps.setString(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("文件来源更新失败 | id={} | userId={}", id, userId, e);
            return false;
        }
    }

    // ==================== 删除 ====================

    /**
     * 软删除文件（将 status 设为 'deleted'）
     *
     * @param id     文件 ID
     * @param userId 用户标识（必填，校验归属）
     * @return true 表示已删除
     */
    public boolean softDelete(Long id, String userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(SOFT_DELETE_BY_ID_AND_USER)) {
            ps.setLong(1, id);
            ps.setString(2, userId);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) {
                log.info("文件已软删除 | id={} | userId={}", id, userId);
            }
            return deleted;
        } catch (SQLException e) {
            log.error("文件软删除失败 | id={} | userId={}", id, userId, e);
            return false;
        }
    }

    // ==================== 内部方法 ====================

    private List<FileMetadata> mapResults(PreparedStatement ps) throws SQLException {
        List<FileMetadata> results = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapMetadata(rs));
            }
        }
        return results;
    }

    private FileMetadata mapMetadata(ResultSet rs) throws SQLException {
        FileMetadata m = new FileMetadata();
        m.setId(rs.getLong("id"));
        m.setUserId(rs.getString("user_id"));
        m.setFilename(rs.getString("filename"));
        m.setStoredName(rs.getString("stored_name"));
        m.setFileType(rs.getString("file_type"));
        m.setSize(rs.getLong("size"));
        m.setCategory(rs.getString("category"));
        m.setExtractedText(rs.getString("extracted_text"));
        m.setSummary(rs.getString("summary"));
        m.setSource(rs.getString("source"));
        m.setStatus(rs.getString("status"));
        m.setCreatedTime(rs.getString("created_time"));
        return m;
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