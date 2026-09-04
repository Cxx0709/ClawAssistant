package com.youkeda.exercise.claw.artifact;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ArtifactService {

    private static final long MAX_ARTIFACT_BYTES = 25L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final Path root;

    public ArtifactService(JdbcTemplate jdbc,
                           @Value("${artifact.storage-path:./data/artifacts}") String storagePath) {
        this.jdbc = jdbc;
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(root);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS generated_artifact (
                    id          TEXT PRIMARY KEY,
                    user_id     TEXT NOT NULL,
                    kind        TEXT NOT NULL,
                    mime_type   TEXT NOT NULL,
                    file_name   TEXT NOT NULL,
                    stored_name TEXT NOT NULL,
                    size        INTEGER NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at  INTEGER NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_generated_artifact_user
                ON generated_artifact(user_id, created_at DESC)
                """);
    }

    public GeneratedArtifact store(String userId, ArtifactKind kind, byte[] content,
                                   String mimeType, String fileName, String description) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId 不能为空");
        if (content == null || content.length == 0) throw new IllegalArgumentException("产物内容不能为空");
        if (content.length > MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("产物超过 25MB 限制");
        String id = UUID.randomUUID().toString();
        String safeName = sanitizeFileName(fileName, kind);
        String storedName = id + extensionOf(safeName);
        Path userRoot = root.resolve(safePathSegment(userId)).normalize();
        Path target = userRoot.resolve(storedName).normalize();
        if (!target.startsWith(userRoot) || !userRoot.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        try {
            Files.createDirectories(userRoot);
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            long now = System.currentTimeMillis();
            jdbc.update("""
                    INSERT INTO generated_artifact
                        (id, user_id, kind, mime_type, file_name, stored_name, size, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, userId, kind.name(), normalizeMime(mimeType), safeName, storedName,
                    content.length, description == null ? "" : description, now);
            return new GeneratedArtifact(id, userId, kind, normalizeMime(mimeType), safeName,
                    content.length, description == null ? "" : description,
                    "/api/artifacts/" + id, Instant.ofEpochMilli(now), null);
        } catch (IOException e) {
            throw new IllegalStateException("保存生成产物失败", e);
        }
    }

    public Optional<StoredArtifact> load(String userId, String id) {
        List<StoredArtifact> rows = jdbc.query("""
                SELECT id, user_id, kind, mime_type, file_name, stored_name, size, description, created_at
                FROM generated_artifact WHERE id = ? AND user_id = ?
                """, (rs, rowNum) -> {
            String storedName = rs.getString("stored_name");
            Path path = root.resolve(safePathSegment(rs.getString("user_id")))
                    .resolve(storedName).normalize();
            return new StoredArtifact(new GeneratedArtifact(
                    rs.getString("id"), rs.getString("user_id"),
                    ArtifactKind.valueOf(rs.getString("kind")), rs.getString("mime_type"),
                    rs.getString("file_name"), rs.getLong("size"), rs.getString("description"),
                    "/api/artifacts/" + rs.getString("id"),
                    Instant.ofEpochMilli(rs.getLong("created_at")), null), path);
        }, id, userId);
        return rows.stream().filter(row -> row.path().startsWith(root) && Files.isRegularFile(row.path())).findFirst();
    }

    public List<GeneratedArtifact> list(String userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT id, user_id, kind, mime_type, file_name, size, description, created_at
                FROM generated_artifact WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> new GeneratedArtifact(
                rs.getString("id"), rs.getString("user_id"), ArtifactKind.valueOf(rs.getString("kind")),
                rs.getString("mime_type"), rs.getString("file_name"), rs.getLong("size"),
                rs.getString("description"), "/api/artifacts/" + rs.getString("id"),
                Instant.ofEpochMilli(rs.getLong("created_at")), null), userId, safeLimit);
    }

    private static String sanitizeFileName(String value, ArtifactKind kind) {
        String fallback = switch (kind) {
            case IMAGE -> "image.png";
            case AUDIO -> "audio.mp3";
            case FILE -> "download.bin";
            case BOARD -> "board.json";
        };
        if (value == null || value.isBlank()) return fallback;
        String name = Path.of(value).getFileName().toString().replaceAll("[\\r\\n\\t]", "_");
        return name.length() > 160 ? name.substring(name.length() - 160) : name;
    }

    private static String safePathSegment(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot).toLowerCase() : "";
    }

    private static String normalizeMime(String mimeType) {
        return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
    }

    public record StoredArtifact(GeneratedArtifact metadata, Path path) {}
}
