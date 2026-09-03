package com.youkeda.exercise.claw.feature.file.tool;
import com.youkeda.exercise.claw.tool.file.FileReadTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.infrastructure.document.FileParseProperties;
import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.file.FileLocalStorage;
import com.youkeda.exercise.claw.feature.file.FileMetadataRepository;
import com.youkeda.exercise.claw.feature.file.FileService;
import com.youkeda.exercise.claw.domain.file.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {

    @TempDir
    Path tempDir;

    private FileReadTool function;
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
        function = new FileReadTool(fileService, registry, objectMapper);
        function.init();
    }

    @Test
    @DisplayName("按 file_id 读取文件")
    void readByFileId() throws Exception {
        FileMetadata meta = fileService.saveFile(userId, "Hello World".getBytes(StandardCharsets.UTF_8), "hello.md");

        String args = "{\"file_id\":" + meta.getId() + "}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertEquals("hello.md", json.get("filename").asText());
        assertEquals("Hello World", json.get("content").asText());
        assertFalse(json.get("truncated").asBoolean());
    }

    @Test
    @DisplayName("按 filename 模糊匹配读取")
    void readByFilename() throws Exception {
        fileService.saveFile(userId, "Java content".getBytes(StandardCharsets.UTF_8), "Java学习笔记.md");

        String args = "{\"filename\":\"Java\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertTrue(json.get("filename").asText().contains("Java"));
    }

    @Test
    @DisplayName("不存在的文件返回错误")
    void nonExistentFile() throws Exception {
        String args = "{\"file_id\":99999}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);
        assertEquals("error", json.get("status").asText());
    }

    @Test
    @DisplayName("缺少参数返回错误")
    void missingArgs() throws Exception {
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