package com.youkeda.exercise.claw.feature.campus.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.domain.campus.ExamPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import org.springframework.dao.EmptyResultDataAccessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class CampusConfigStore {

    private static final Logger log = LoggerFactory.getLogger(CampusConfigStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CampusConfigStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CampusConfig get() {
        try {
            return jdbc.queryForObject("SELECT * FROM campus_config ORDER BY id DESC LIMIT 1", new ConfigRowMapper());
        } catch (EmptyResultDataAccessException e) {
            log.debug("尚无 campus_config 记录");
            return null;
        } catch (Exception e) {
            log.warn("查询 campus_config 失败", e);
            return null;
        }
    }

    public void save(CampusConfig config) {
        String extraJson;
        try {
            extraJson = objectMapper.writeValueAsString(
                config.getPreferences() != null ? config.getPreferences() : new ExamPreferences());
        } catch (Exception e) {
            log.error("序列化 ExamPreferences 失败", e);
            throw new RuntimeException("序列化 ExamPreferences 失败", e);
        }

        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            INSERT INTO campus_config (school, class_name, enabled, extra_config, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, config.getSchool(), config.getClassName(),
            config.isEnabled() ? 1 : 0, extraJson, now, now);
    }

    public void updatePreferences(String extraConfigJson) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update(
            "UPDATE campus_config SET extra_config = ?, updated_at = ? WHERE enabled = 1",
            extraConfigJson, now);
        if (updated == 0) {
            log.warn("更新偏好失败：无有效 campus_config 记录");
        }
    }

    private class ConfigRowMapper implements RowMapper<CampusConfig> {
        @Override
        public CampusConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            CampusConfig config = new CampusConfig();
            config.setId(rs.getLong("id"));
            config.setSchool(rs.getString("school"));
            config.setClassName(rs.getString("class_name"));
            config.setEnabled(rs.getInt("enabled") == 1);
            config.setCreatedAt(Instant.ofEpochSecond(rs.getLong("created_at")));
            config.setUpdatedAt(Instant.ofEpochSecond(rs.getLong("updated_at")));

            String extra = rs.getString("extra_config");
            if (extra != null && !extra.isBlank()) {
                try {
                    ExamPreferences prefs = objectMapper.readValue(extra, ExamPreferences.class);
                    config.setPreferences(prefs);
                } catch (Exception e) {
                    log.warn("反序列化 ExamPreferences 失败 | json={}", extra, e);
                }
            }
            return config;
        }
    }
}
