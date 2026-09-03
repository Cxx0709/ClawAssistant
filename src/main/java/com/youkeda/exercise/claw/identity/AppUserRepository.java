package com.youkeda.exercise.claw.identity;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AppUserRepository {

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    id            TEXT PRIMARY KEY,
                    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    display_name  TEXT NOT NULL,
                    enabled       INTEGER NOT NULL DEFAULT 1,
                    created_at    INTEGER NOT NULL
                )
                """);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return count == null ? 0 : count;
    }

    public Optional<AppUser> findByUsername(String username) {
        List<AppUser> users = jdbc.query("""
                SELECT id, username, password_hash, display_name, enabled, created_at
                FROM app_user WHERE username = ? COLLATE NOCASE
                """, (rs, rowNum) -> map(rs), username);
        return users.stream().findFirst();
    }

    public Optional<AppUser> findById(String id) {
        List<AppUser> users = jdbc.query("""
                SELECT id, username, password_hash, display_name, enabled, created_at
                FROM app_user WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);
        return users.stream().findFirst();
    }

    public AppUser create(String id, String username, String passwordHash, String displayName) {
        try {
            long now = System.currentTimeMillis();
            jdbc.update("""
                    INSERT INTO app_user(id, username, password_hash, display_name, enabled, created_at)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, id, username, passwordHash, displayName, now);
            return new AppUser(id, username, passwordHash, displayName, true, Instant.ofEpochMilli(now));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在", e);
        }
    }

    private static AppUser map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AppUser(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getInt("enabled") != 0,
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }
}
