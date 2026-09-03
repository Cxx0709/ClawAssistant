package com.youkeda.exercise.claw.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** One-time compatibility reader. No runtime feature depends on the legacy database. */
@Component
public class LegacyOwnerImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyOwnerImporter.class);
    private final Path legacyDatabase;

    public LegacyOwnerImporter(@Value("${legacy.owner-db-path:./data/claw-bot.db}") String path) {
        this.legacyDatabase = Path.of(path).toAbsolutePath().normalize();
    }

    public LegacyOwner findOwner() {
        if (!Files.isRegularFile(legacyDatabase)) return null;
        String url = "jdbc:sqlite:" + legacyDatabase;
        try (var connection = DriverManager.getConnection(url)) {
            try (var check = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='wechat_users'");
                 var result = check.executeQuery()) {
                if (!result.next()) return null;
            }
            try (var query = connection.prepareStatement("""
                    SELECT user_id, school_id FROM wechat_users
                    ORDER BY first_active_time ASC, rowid ASC LIMIT 1
                    """); var result = query.executeQuery()) {
                if (!result.next()) return null;
                Object schoolValue = result.getObject("school_id");
                Long schoolId = schoolValue instanceof Number number ? number.longValue() : null;
                return new LegacyOwner(result.getString("user_id"), schoolId);
            }
        } catch (Exception e) {
            log.warn("旧用户数据读取失败，将创建全新 Web 用户 | path={} | error={}",
                    legacyDatabase, e.getMessage());
            return null;
        }
    }

    public record LegacyOwner(String userId, Long schoolId) {}
}
