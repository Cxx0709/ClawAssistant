package com.youkeda.exercise.claw.feature.file;

import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import com.youkeda.exercise.claw.domain.file.FileMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件服务测试
 *
 * <p>测试 {@link FileService} 的核心功能，包括保存、读取、搜索、
 * 用户隔离、文件类型白名单、大小限制和路径穿越防护。
 *
 * <p>使用临时目录隔离数据库和文件存储，不依赖 Spring 容器。
 */
class FileServiceTest {

    @TempDir
    Path tempDir;

    private FileService fileService;
    private FileMetadataRepository metadataRepository;
    private FileLocalStorage fileLocalStorage;
    private FileParseService fileParseService;
    private String userA = "userA";
    private String userB = "userB";

    @BeforeEach
    void setUp() {
        // 初始化 FileParseService
        var parseProperties = new com.youkeda.exercise.claw.infrastructure.document.FileParseProperties();
        parseProperties.setMaxFileSize(10 * 1024 * 1024);
        parseProperties.setMaxTextLength(10000);
        parseProperties.setMaxEmbeddedImages(5);
        fileParseService = new FileParseService(parseProperties);

        // 初始化 FileMetadataRepository（使用临时 DB）
        metadataRepository = new FileMetadataRepository();
        setField(metadataRepository, "dbPath", new File(tempDir.toFile(), "claw-files.db").getAbsolutePath());
        metadataRepository.init();

        // 初始化 FileLocalStorage
        fileLocalStorage = new FileLocalStorage();
        setField(fileLocalStorage, "filesRoot", tempDir.resolve("users").toFile().getAbsolutePath());
        setField(fileLocalStorage, "maxFileSize", 20 * 1024 * 1024L);

        // 初始化 FileService
        fileService = new FileService(fileLocalStorage, metadataRepository, fileParseService, fileLocalStorage);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 文件保存 ====================

    @Nested
    @DisplayName("文件保存")
    class SaveFileTest {

        @Test
        @DisplayName("保存 md 文件")
        void saveMdFile() {
            String content = "# 操作系统笔记\n\n## 进程管理\n进程是...";
            FileMetadata meta = fileService.saveFile(userA, content.getBytes(StandardCharsets.UTF_8), "操作系统笔记.md");

            assertNotNull(meta);
            assertNotNull(meta.getId());
            assertEquals(userA, meta.getUserId());
            assertEquals("操作系统笔记.md", meta.getFilename());
            assertEquals("md", meta.getFileType());
            assertTrue(meta.getStoredName().endsWith(".md"));
            assertTrue(meta.getSize() > 0);
            assertEquals("active", meta.getStatus());

            // 验证文件已写入磁盘
            Path filePath = fileLocalStorage.resolvePath(userA, meta.getStoredName());
            assertTrue(Files.exists(filePath));

            // 验证元数据已入库
            FileMetadata fromDb = metadataRepository.findByIdAndUserId(meta.getId(), userA);
            assertNotNull(fromDb);
            assertEquals(meta.getFilename(), fromDb.getFilename());
        }

        @Test
        @DisplayName("保存 txt 文件")
        void saveTxtFile() {
            String content = "纯文本内容";
            FileMetadata meta = fileService.saveFile(userA, content.getBytes(StandardCharsets.UTF_8), "readme.txt");

            assertNotNull(meta);
            assertEquals("txt", meta.getFileType());
        }

        @Test
        @DisplayName("拒绝不支持的 .exe 文件")
        void rejectExeFile() {
            byte[] content = "fake exe".getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class,
                    () -> fileService.saveFile(userA, content, "virus.exe"));
        }

        @Test
        @DisplayName("拒绝超过大小限制的文件")
        void rejectOversizeFile() {
            byte[] content = new byte[21 * 1024 * 1024]; // 21MB
            assertThrows(IllegalArgumentException.class,
                    () -> fileService.saveFile(userA, content, "big.pdf"));
        }

        @Test
        @DisplayName("空内容拒绝保存")
        void rejectEmptyContent() {
            assertThrows(IllegalArgumentException.class,
                    () -> fileService.saveFile(userA, new byte[0], "empty.txt"));
        }

        @Test
        @DisplayName("空 userId 拒绝保存")
        void rejectEmptyUserId() {
            assertThrows(IllegalArgumentException.class,
                    () -> fileService.saveFile("  ", "content".getBytes(), "test.txt"));
        }
    }

    // ==================== 用户隔离 ====================

    @Nested
    @DisplayName("用户隔离")
    class UserIsolationTest {

        @Test
        @DisplayName("UserB 不能读取 UserA 的文件")
        void userBCannotReadUserAFile() {
            fileService.saveFile(userA, "UserA's file".getBytes(StandardCharsets.UTF_8), "a.txt");

            // UserA 能看到自己的文件
            List<FileMetadata> userAFiles = metadataRepository.findByUserId(userA);
            assertEquals(1, userAFiles.size());

            // UserB 看不到
            List<FileMetadata> userBFiles = metadataRepository.findByUserId(userB);
            assertTrue(userBFiles.isEmpty());
        }

        @Test
        @DisplayName("findByIdAndUserId 校验用户归属")
        void findByIdAndUserIdIsolation() {
            FileMetadata meta = fileService.saveFile(userA, "secret".getBytes(StandardCharsets.UTF_8), "secret.txt");

            // UserA 可以查到
            assertNotNull(metadataRepository.findByIdAndUserId(meta.getId(), userA));

            // UserB 查不到
            assertNull(metadataRepository.findByIdAndUserId(meta.getId(), userB));
        }

        @Test
        @DisplayName("搜索只返回当前用户文件")
        void searchIsolation() {
            fileService.saveFile(userA, "Java学习笔记".getBytes(StandardCharsets.UTF_8), "Java学习笔记.md");
            fileService.saveFile(userB, "Java面试题".getBytes(StandardCharsets.UTF_8), "Java面试题.md");

            List<FileMetadata> userAResults = metadataRepository.search(userA, "Java", null, 10);
            assertEquals(1, userAResults.size());
            assertEquals("Java学习笔记.md", userAResults.get(0).getFilename());
        }
    }

    // ==================== 文件读取 ====================

    @Nested
    @DisplayName("文件读取")
    class ReadFileTest {

        @Test
        @DisplayName("读取 md 文件内容")
        void readMdFile() {
            String content = "# Java 笔记\n\n## 集合框架\nArrayList 底层是数组...";
            FileMetadata meta = fileService.saveFile(userA, content.getBytes(StandardCharsets.UTF_8), "Java笔记.md");

            FileService.FileContent fileContent = fileService.readFileText(userA, meta.getId());
            assertNotNull(fileContent);
            assertEquals(content, fileContent.content());
            assertFalse(fileContent.truncated());
        }

        @Test
        @DisplayName("读取超过 5000 字符的文件时截断")
        void readTruncatedFile() {
            // 构造 6000 字符
            String content = "A".repeat(6000);
            FileMetadata meta = fileService.saveFile(userA, content.getBytes(StandardCharsets.UTF_8), "long.txt");

            FileService.FileContent fileContent = fileService.readFileText(userA, meta.getId());
            assertNotNull(fileContent);
            assertTrue(fileContent.truncated());
            assertEquals(5000, fileContent.content().length());
            assertNotNull(fileContent.message());
        }

        @Test
        @DisplayName("读取不存在的文件返回 null")
        void readNonExistentFile() {
            assertNull(fileService.readFileText(userA, 99999L));
        }

        @Test
        @DisplayName("UserB 不能读取 UserA 的文件")
        void userBCannotRead() {
            FileMetadata meta = fileService.saveFile(userA, "secret".getBytes(StandardCharsets.UTF_8), "secret.txt");
            assertNull(fileService.readFileText(userB, meta.getId()));
        }
    }

    // ==================== 文件搜索 ====================

    @Nested
    @DisplayName("文件搜索")
    class SearchFileTest {

        @Test
        @DisplayName("按关键词搜索文件")
        void searchByKeyword() {
            fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "数据库笔记.md");
            fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "Java面试题.md");

            List<FileMetadata> results = fileService.searchFiles(userA, "数据库", null, 10);
            assertEquals(1, results.size());
            assertEquals("数据库笔记.md", results.get(0).getFilename());
        }

        @Test
        @DisplayName("搜索不存在的关键词返回空列表")
        void searchNonExistent() {
            fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "test.md");
            assertTrue(fileService.searchFiles(userA, "不存在的关键词", null, 10).isEmpty());
        }

        @Test
        @DisplayName("空关键词返回空列表")
        void emptyKeyword() {
            fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "test.md");
            assertTrue(fileService.searchFiles(userA, "", null, 10).isEmpty());
        }

        @Test
        @DisplayName("limit 限制返回数量")
        void limitResults() {
            for (int i = 0; i < 5; i++) {
                fileService.saveFile(userA, ("content" + i).getBytes(StandardCharsets.UTF_8), "file" + i + ".md");
            }
            List<FileMetadata> results = fileService.searchFiles(userA, "file", null, 3);
            assertTrue(results.size() <= 3);
        }
    }

    // ==================== 路径穿越防护 ====================

    @Nested
    @DisplayName("路径穿越防护")
    class PathTraversalTest {

        @Test
        @DisplayName("storedName 含 ../../etc/passwd 被拒绝")
        void basicPathTraversalRejected() {
            assertThrows(SecurityException.class,
                    () -> fileLocalStorage.resolvePath(userA, "../../etc/passwd"));
        }

        @Test
        @DisplayName("深度嵌套的 ../ 被拒绝")
        void deepPathTraversalRejected() {
            assertThrows(SecurityException.class,
                    () -> fileLocalStorage.resolvePath(userA, "a/../../../etc/shadow"));
        }

        @Test
        @DisplayName("含 .. 但仍在用户目录内被视为有效")
        void dotDotWithinUserDirIsValid() {
            // sub/../file.txt → file.txt（已在用户目录内）
            assertDoesNotThrow(() -> fileLocalStorage.resolvePath(userA, "sub/../file.txt"));
        }
    }

    // ==================== 文件软删除 ====================

    @Nested
    @DisplayName("文件软删除")
    class DeleteFileTest {

        @Test
        @DisplayName("软删除后不再出现在列表")
        void softDelete() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "delete.md");

            assertTrue(metadataRepository.findByUserId(userA).size() == 1);

            boolean deleted = fileService.deleteFile(userA, meta.getId());
            assertTrue(deleted);

            // 不再出现在活跃列表
            assertTrue(metadataRepository.findByUserId(userA).isEmpty());

            // 但仍然可以通过 ID+User 查到（因为 status='deleted'不会被查到）
            assertNull(metadataRepository.findByIdAndUserId(meta.getId(), userA));
        }

        @Test
        @DisplayName("UserB 不能删除 UserA 的文件")
        void userBCannotDelete() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "secret.md");
            assertFalse(fileService.deleteFile(userB, meta.getId()));
        }
    }

    // ==================== 文件更新 ====================

    @Nested
    @DisplayName("文件更新")
    class UpdateFileTest {

        @Test
        @DisplayName("重命名文件")
        void renameFile() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "old.md");

            FileMetadata updated = fileService.updateFileName(userA, meta.getId(), "new.md");
            assertNotNull(updated);
            assertEquals("new.md", updated.getFilename());
        }

        @Test
        @DisplayName("UserB 不能重命名 UserA 的文件")
        void userBCannotRename() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "secret.md");
            assertNull(fileService.updateFileName(userB, meta.getId(), "new.md"));
        }

        @Test
        @DisplayName("不支持的扩展名被拒绝")
        void rejectInvalidExtension() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "test.md");
            assertThrows(IllegalArgumentException.class,
                    () -> fileService.updateFileName(userA, meta.getId(), "test.exe"));
        }
    }

    // ==================== 文件来源标记 ====================

    @Nested
    @DisplayName("文件来源标记")
    class SourceFieldTest {

        @Test
        @DisplayName("通过 FileHandler 保存的文件标记为 user_upload")
        void userUploadSource() {
            FileMetadata meta = fileService.saveFile(userA, "content".getBytes(StandardCharsets.UTF_8), "upload.md");
            assertEquals("user_upload", meta.getSource());
        }

        @Test
        @DisplayName("通过 saveTextFile 保存的文件标记为 agent_save")
        void agentSaveSource() {
            FileMetadata meta = fileService.saveTextFile(userA, "content", "agent.md");
            assertEquals("agent_save", meta.getSource());
        }
    }
}