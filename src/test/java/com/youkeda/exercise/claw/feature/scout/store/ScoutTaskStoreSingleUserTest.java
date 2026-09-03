package com.youkeda.exercise.claw.feature.scout.store;

import com.youkeda.exercise.claw.feature.scout.task.ScoutTaskStatus;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScoutTaskStoreSingleUserTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyUserScopedTableAndStoresTasksWithoutUserId() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("scout.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE scout_tasks (
                    task_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    query TEXT,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    summary TEXT
                )
                """);
        jdbc.update("""
                INSERT INTO scout_tasks
                (task_id, user_id, query, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, "legacy-task", "legacy-owner", "legacy query", "COMPLETED", 1L);

        ScoutTaskStore store = new ScoutTaskStore(jdbc);
        store.save(new ScoutTask("new-task", "new query", ScoutTaskStatus.PENDING,
                Instant.now(), null, null));

        var columns = jdbc.queryForList("PRAGMA table_info(scout_tasks)");
        assertFalse(columns.stream().anyMatch(row -> "user_id".equals(row.get("name"))));
        assertEquals(2, store.findAll().size());
    }
}
