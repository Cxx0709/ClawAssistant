package com.youkeda.exercise.claw.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactServiceTest {

    @TempDir Path tempDir;
    private ArtifactService artifacts;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        artifacts = new ArtifactService(new JdbcTemplate(dataSource), tempDir.toString());
        artifacts.init();
    }

    @Test
    void onlyOwnerCanLoadStoredArtifact() throws Exception {
        byte[] content = "private data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        GeneratedArtifact stored = artifacts.store("alice", ArtifactKind.FILE, content,
                "text/plain", "../notes.txt", "test");

        assertEquals("notes.txt", stored.fileName());
        assertTrue(artifacts.load("bob", stored.id()).isEmpty());
        var ownerCopy = artifacts.load("alice", stored.id()).orElseThrow();
        assertArrayEquals(content, java.nio.file.Files.readAllBytes(ownerCopy.path()));
        assertEquals(1, artifacts.list("alice", 10).size());
        assertTrue(artifacts.list("bob", 10).isEmpty());
    }
}
