package com.youkeda.exercise.claw.feature.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储实现
 *
 * <p>将文件保存到 {@code data/users/{userId}/files/original/} 目录下，
 * 以 {@code userId} 做目录级用户隔离。支持白名单校验和大小限制。
 *
 * <p>实现 {@link FileStorage} 接口，可替换为 OSS/S3 等远端存储。
 */
@Component
public class FileLocalStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileLocalStorage.class);

    /** 用户文件根目录 */
    static final String ORIGINAL_DIR = "files" + File.separator + "original";

    /** 用户文件根目录（可通过 file.storage-root 配置） */
    private String filesRoot;

    /** 允许的文件扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "md", "txt", "pdf", "docx",
            "xlsx", "xls",
            "png", "jpg", "jpeg"
    );

    /** 存储基目录（用于路径穿越校验） */
    private static final String STORAGE_BASE_DIR = "data" + File.separator + "users";

    @Value("${file.max-size:20971520}")
    private long maxFileSize;

    public FileLocalStorage() {
        this.filesRoot = STORAGE_BASE_DIR;
    }

    /**
     * 设置用户文件根目录（由 Spring 从 file.storage-root 注入）
     */
    @Value("${file.storage-root:}")
    public void setFilesRoot(String root) {
        if (root != null && !root.isBlank()) {
            this.filesRoot = root;
        }
    }

    @Override
    public String save(String userId, byte[] content, String extension) {
        if (!isAllowedExtension(extension)) {
            throw new IllegalArgumentException("不支持的文件类型: ." + extension);
        }
        if (!isWithinSizeLimit(content)) {
            throw new IllegalArgumentException("文件超过大小限制 (" + (maxFileSize / 1024 / 1024) + "MB)");
        }

        // 生成唯一存储名：UUID + 原始扩展名
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        try {
            Path targetPath = resolvePath(userId, storedName);
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, content);
            log.info("文件已保存 | userId={} | storedName={} | size={}", userId, storedName, content.length);
            return storedName;
        } catch (SecurityException e) {
            log.error("文件保存路径非法 | userId={}", userId, e);
            throw new IllegalArgumentException("非法文件路径");
        } catch (IOException e) {
            log.error("文件保存失败 | userId={} | storedName={}", userId, storedName, e);
            throw new RuntimeException("文件保存失败", e);
        }
    }

    @Override
    public byte[] read(String userId, String storedName) {
        try {
            Path path = resolvePath(userId, storedName);
            if (!Files.exists(path)) {
                log.warn("文件不存在 | userId={} | storedName={}", userId, storedName);
                return null;
            }
            return Files.readAllBytes(path);
        } catch (SecurityException e) {
            log.error("文件读取路径非法 | userId={} | storedName={}", userId, storedName, e);
            return null;
        } catch (IOException e) {
            log.error("文件读取失败 | userId={} | storedName={}", userId, storedName, e);
            return null;
        }
    }

    @Override
    public boolean delete(String userId, String storedName) {
        try {
            Path path = resolvePath(userId, storedName);
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                log.info("文件已删除 | userId={} | storedName={}", userId, storedName);
            }
            return deleted;
        } catch (SecurityException e) {
            log.error("文件删除路径非法 | userId={} | storedName={}", userId, storedName, e);
            return false;
        } catch (IOException e) {
            log.error("文件删除失败 | userId={} | storedName={}", userId, storedName, e);
            return false;
        }
    }

    @Override
    public boolean isAllowedExtension(String extension) {
        return extension != null && ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public boolean isWithinSizeLimit(byte[] content) {
        return content != null && content.length <= maxFileSize;
    }

    /**
     * 解析文件路径，并进行路径穿越防护
     *
     * <p>最终路径格式：{@code data/users/{userId}/files/original/{storedName}}
     *
     * <p>防护措施：
     * <ul>
     *   <li>userId 去除路径分隔符和父目录引用，防止用户越权访问其他用户目录</li>
     *   <li>normalize + startsWith 确保 storedName 中的 {@code ../} 无法逃逸出用户目录</li>
     * </ul>
     */
    Path resolvePath(String userId, String storedName) {
        Path baseDir = Paths.get(filesRoot).normalize().toAbsolutePath();

        // 清理 userId：移除路径分隔符和父目录引用
        String safeUserId = sanitizeUserId(userId);
        if (safeUserId == null || safeUserId.isBlank()) {
            throw new SecurityException("非法用户标识");
        }

        // 构建用户目录并校验不能逃逸基目录
        Path root = baseDir.resolve(safeUserId).resolve(ORIGINAL_DIR).normalize();
        if (!root.startsWith(baseDir)) {
            throw new SecurityException("非法用户路径: " + userId);
        }

        // 解析文件路径并校验不能逃逸用户目录
        Path filePath = root.resolve(storedName).normalize();
        if (storedName != null && !storedName.isBlank() && !filePath.startsWith(root)) {
            throw new SecurityException("非法路径访问: " + storedName);
        }

        return filePath;
    }

    /**
     * 清理 userId：去除路径分隔符和父目录引用，只保留安全字符。
     *
     * @param userId 原始用户标识
     * @return 安全的路径段，如果全部非法返回空字符串
     */
    private String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) return "";
        // 只允许字母、数字、下划线、连字符、点号
        String sanitized = userId.replaceAll("[^a-zA-Z0-9_\\-.@]", "");
        // 如果清理后为空或者清理前后差异很大（说明包含大量非法字符），返回空
        if (sanitized.isBlank()) return "";
        return sanitized;
    }

    /**
     * 提取文件扩展名（不含点）
     *
     * @param filename 原始文件名
     * @return 扩展名，如 "md"，无法识别返回空字符串
     */
    public String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * 获取用户原始文件目录
     */
    Path getUserOriginalDir(String userId) {
        Path baseDir = Paths.get(filesRoot).normalize().toAbsolutePath();
        String safeUserId = sanitizeUserId(userId);
        if (safeUserId == null || safeUserId.isBlank()) {
            throw new SecurityException("非法用户标识");
        }
        Path userDir = baseDir.resolve(safeUserId).resolve(ORIGINAL_DIR).normalize();
        if (!userDir.startsWith(baseDir)) {
            throw new SecurityException("非法用户路径: " + userId);
        }
        return userDir;
    }
}
