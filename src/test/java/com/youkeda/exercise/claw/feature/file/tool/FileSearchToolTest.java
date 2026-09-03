package com.youkeda.exercise.claw.feature.file.tool;
import com.youkeda.exercise.claw.tool.file.FileSearchTool;

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

class FileSearchToolTest {

    @TempDir
    Path tempDir;

    private FileSearchTool function;
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
        function = new FileSearchTool(fileService, registry, objectMapper);
        function.init();
    }

    @Test
    @DisplayName("按关键词搜索文件")
    void searchByKeyword() throws Exception {
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "数据库笔记.md");
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "Java面试题.md");

        String args = "{\"keyword\":\"数据库\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(1, json.get("total").asInt());
        assertEquals("数据库笔记.md", json.get("files").get(0).get("filename").asText());
    }

    @Test
    @DisplayName("搜索不存在关键词返回空列表")
    void searchNonExistent() throws Exception {
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "test.md");

        String args = "{\"keyword\":\"不存在的\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(0, json.get("total").asInt());
    }

    @Test
    @DisplayName("按文件类型过滤")
    void filterByType() throws Exception {
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "笔记.md");
        fileService.saveFile(userId, "content".getBytes(StandardCharsets.UTF_8), "文档.pdf");

        String args = "{\"keyword\":\"笔记\",\"file_type\":\"md\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals(1, json.get("total").asInt());
        assertEquals("笔记.md", json.get("files").get(0).get("filename").asText());
    }

    @Test
    @DisplayName("缺少关键词返回错误")
    void missingKeyword() throws Exception {
        String args = "{}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);
        assertEquals("error", json.get("status").asText());
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