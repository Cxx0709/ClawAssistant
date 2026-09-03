package com.youkeda.exercise.claw.feature.file.tool;
import com.youkeda.exercise.claw.tool.file.FileSaveTool;

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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSaveToolTest {

    @TempDir
    Path tempDir;

    private FileSaveTool function;
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
        function = new FileSaveTool(fileService, registry, objectMapper);
        function.init();
    }

    @Test
    @DisplayName("保存内容到文件")
    void saveContent() throws Exception {
        String args = "{\"filename\":\"test.md\",\"content\":\"# Hello\\n\\nThis is a test\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("success", json.get("status").asText());
        assertTrue(json.has("file_id"));
        assertEquals("test.md", json.get("filename").asText());
        assertTrue(json.get("size").asInt() > 0);
    }

    @Test
    @DisplayName("缺少文件名返回错误")
    void missingFilename() throws Exception {
        String args = "{\"content\":\"some content\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);
        assertEquals("error", json.get("status").asText());
    }

    @Test
    @DisplayName("缺少内容返回错误")
    void missingContent() throws Exception {
        String args = "{\"filename\":\"test.md\"}";
        String result = function.execute(args, context(userId));
        JsonNode json = objectMapper.readTree(result);
        assertEquals("error", json.get("status").asText());
    }

    @Test
    @DisplayName("不支持的扩展名返回错误")
    void unsupportedExtension() throws Exception {
        String args = "{\"filename\":\"test.exe\",\"content\":\"bad\"}";
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