package com.youkeda.exercise.claw.feature.scout.store;

import com.youkeda.exercise.claw.feature.scout.VectorUtils;
import com.youkeda.exercise.claw.feature.scout.processor.InformationIdentity;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scout 信息的 SQLite 存储。
 *
 * <p>资讯正文和 embedding 一起保存在本地数据库。Scout 的数据量小且只有短 TTL，
 * 直接在 Java 中计算余弦相似度即可，无需为打包后的个人助手额外部署向量数据库。
 */
@Component
@ConditionalOnProperty(name = "scout.enabled", havingValue = "true")
public class SqliteInformationStore implements InformationStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteInformationStore.class);

    private static final String UPSERT_SQL = """
            INSERT INTO scout_information (
                id, title, content, source, source_type, category,
                published_at, collected_at, summary, embedding
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                content = excluded.content,
                source = excluded.source,
                source_type = excluded.source_type,
                category = excluded.category,
                published_at = excluded.published_at,
                collected_at = excluded.collected_at,
                summary = excluded.summary,
                embedding = excluded.embedding
            """;

    private static final String SELECT_COLUMNS = """
            id, title, content, source, source_type, category,
            published_at, collected_at, summary, embedding
            """;

    private final JdbcTemplate jdbc;

    public SqliteInformationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scout_information (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT '',
                    source_type TEXT NOT NULL DEFAULT '',
                    category TEXT NOT NULL DEFAULT '',
                    published_at INTEGER NOT NULL DEFAULT 0,
                    collected_at INTEGER NOT NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    embedding BLOB
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scout_information_recent
                ON scout_information(collected_at DESC)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scout_information_published
                ON scout_information(published_at DESC)
                """);
        log.info("Scout SQLite 信息库初始化完成");
    }

    @Override
    @Transactional
    public void batchSave(List<InformationItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<InformationItem> validItems = items.stream()
                .filter(item -> item != null && item.getTitle() != null)
                .toList();
        if (validItems.isEmpty()) {
            return;
        }

        jdbc.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                InformationItem item = validItems.get(index);
                String id = InformationIdentity.pointUuid(item);
                item.setId(id);
                ps.setString(1, id);
                ps.setString(2, item.getTitle());
                ps.setString(3, safe(item.getContent()));
                ps.setString(4, safe(item.getSource()));
                ps.setString(5, safe(item.getSourceType()));
                ps.setString(6, safe(item.getCategory()));
                ps.setLong(7, item.getPublishedAt());
                ps.setLong(8, item.getCollectedAt());
                ps.setString(9, safe(item.getSummary()));
                byte[] embedding = vectorToBytes(item.getVector());
                if (embedding == null) {
                    ps.setNull(10, Types.BLOB);
                } else {
                    ps.setBytes(10, embedding);
                }
            }

            @Override
            public int getBatchSize() {
                return validItems.size();
            }
        });
        log.debug("Scout 信息批量写入 SQLite | count={}", validItems.size());
    }

    @Override
    public List<InformationItem> searchByVector(float[] vector, int topK) {
        if (vector == null || vector.length == 0 || topK <= 0) {
            return List.of();
        }
        List<InformationItem> items = jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM scout_information WHERE embedding IS NOT NULL",
                (rs, rowNum) -> mapRow(rs));

        List<ScoredItem> scored = new ArrayList<>();
        for (InformationItem item : items) {
            float score = VectorUtils.cosineSimilarity(vector, item.getVector());
            if (score >= 0f) {
                scored.add(new ScoredItem(item, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
                .limit(topK)
                .map(ScoredItem::item)
                .toList();
    }

    @Override
    public List<InformationItem> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS
                        + " FROM scout_information"
                        + " ORDER BY collected_at DESC LIMIT ?",
                (rs, rowNum) -> mapRow(rs),
                limit);
    }

    @Override
    public void deleteExpired(long beforeTimestamp) {
        int deleted = jdbc.update(
                "DELETE FROM scout_information WHERE collected_at < ?",
                beforeTimestamp);
        if (deleted > 0) {
            log.info("Scout SQLite 过期信息已清理 | count={}", deleted);
        }
    }

    private InformationItem mapRow(java.sql.ResultSet rs) throws SQLException {
        InformationItem item = new InformationItem();
        item.setId(rs.getString("id"));
        item.setTitle(rs.getString("title"));
        item.setContent(rs.getString("content"));
        item.setSource(rs.getString("source"));
        item.setSourceType(rs.getString("source_type"));
        item.setCategory(rs.getString("category"));
        item.setPublishedAt(rs.getLong("published_at"));
        item.setCollectedAt(rs.getLong("collected_at"));
        item.setSummary(rs.getString("summary"));
        item.setVector(bytesToVector(rs.getBytes("embedding")));
        return item;
    }

    private byte[] vectorToBytes(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] bytesToVector(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredItem(InformationItem item, float score) {
    }
}
