package com.youkeda.exercise.claw.feature.file.tool;
import com.youkeda.exercise.claw.tool.file.FileListTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.infrastructure.document.FileParseProperties;
import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.file.FileLocalStorage;
import com.youkeda.exercise.claw.feature.file.FileMetadataRepository;
import com.youkeda.exercise.claw.feature.file.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileListToolTest {

    @TempDir
    Path tempDir;

    private FileListTool function;
    private FileService fileService;
    private ObjectMapper objectMapper;
    private String userId = "testUser";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        var parseProperties = new FileParseProperties();
        parseProperties.setMaxFileSize(10 * 1024 * 1024);
        parseProperties.setMaxTextLength(10000);
        parseProperties.setMaxEmbeddedImages(5);
        var fileParseService = new FileParseService(parseProperties);

        var metadataRepository = new FileMetadataRepository();
        setField(metadataRepository, "dbPath", new File(tempDir.toFile(), "claw-files.db").getAbsolutePath());
        metadataRepository.init();

        var fileLocalStorage = new FileLocalStorage();
        setField(fileLocalStorage, "filesRoot", tempDir.resolve("users").toFile().getAbsolutePath());
        setField(fileLocalStorage, "maxFileSize", 20 * 1024 * 1024L);

        fileService = new FileService(fileLocalStorage, metadataRepository, fileParseService, fileLocalStorage);

        var registry = new ToolRegistry();
        function = new FileListTool(fileService, registry, objectMapper);
        function.init();
    }

    @Test
    @DisplayName("列出用户文件列表")
    void listFiles() throws Exception {
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "笔记.md");
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "文档.pdf");

        String result = function.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(2, json.get("total").asInt());
        assertEquals(1, json.get("page").asInt());
        assertTrue(json.get("files").isArray());
        assertEquals(2, json.get("files").size());
    }

    @Test
    @DisplayName("无文件时返回空列表")
    void emptyList() throws Exception {
        String result = function.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(0, json.get("total").asInt());
        assertTrue(json.get("files").isArray());
        assertTrue(json.get("files").isEmpty());
    }

    @Test
    @DisplayName("支持分页参数")
    void pagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            fileService.saveFile(userId, ("content" + i).getBytes(StandardCharsets.UTF_8), "file" + i + ".md");
        }

        // 第 1 页，每页 2 条
        String result = function.execute("{\"page\":1,\"size\":2}", context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(5, json.get("total").asInt());
        assertEquals(1, json.get("page").asInt());
        assertEquals(2, json.get("page_size").asInt());
        assertEquals(3, json.get("total_pages").asInt());
        assertTrue(json.get("has_more").asBoolean());
        assertEquals(2, json.get("files").size());
    }

    @Test
    @DisplayName("返回的文件包含必要的字段")
    void fileFields() throws Exception {
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "test.md");

        String result = function.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);
        JsonNode file = json.get("files").get(0);

        assertTrue(file.has("file_id"));
        assertTrue(file.has("filename"));
        assertTrue(file.has("file_type"));
        assertTrue(file.has("size"));
        assertTrue(file.has("size_display"));
        assertTrue(file.has("created_time"));
        assertEquals("test.md", file.get("filename").asText());
    }

    @Test
    @DisplayName("用户隔离：不返回其他用户的文件")
    void userIsolation() throws Exception {
        fileService.saveFile("otherUser", "content".getBytes(StandardCharsets.UTF_8), "secret.md");

        String result = function.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);
        assertEquals(0, json.get("total").asInt());
    }

    private ToolExecutionContext context(String userId) {
        return new ToolExecutionContext("", null, userId);
    }

    private void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}