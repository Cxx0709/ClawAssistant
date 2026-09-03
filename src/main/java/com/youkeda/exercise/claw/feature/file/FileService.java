package com.youkeda.exercise.claw.feature.file;

import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import com.youkeda.exercise.claw.domain.file.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件业务服务
 *
 * <p>提供文件的保存、读取、搜索等核心业务能力。
 * 依赖 {@link FileStorage} 接口进行文件读写（可切换本地/对象存储），
 * 依赖 {@link FileMetadataRepository} 管理元数据。
 *
 * <p>所有方法强制 {@code userId} 参数确保用户隔离。
 * 文件保存失败不影响调用方的现有流程（异常内部捕获并记录日志）。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 文本类型文件（可全文阅读） */
    private static final java.util.Set<String> TEXT_TYPES = java.util.Set.of("md", "txt");

    /** 文件内容读取最大长度 */
    static final int MAX_READ_LENGTH = 5000;

    private final FileStorage fileStorage;
    private final FileMetadataRepository metadataRepository;
    private final FileParseService fileParseService;
    private final FileLocalStorage fileLocalStorage; // 用于 extractExtension

    public FileService(FileStorage fileStorage,
                       FileMetadataRepository metadataRepository,
                       FileParseService fileParseService,
                       FileLocalStorage fileLocalStorage) {
        this.fileStorage = fileStorage;
        this.metadataRepository = metadataRepository;
        this.fileParseService = fileParseService;
        this.fileLocalStorage = fileLocalStorage;
    }

    // ==================== 保存 ====================

    /**
     * 保存文件并注册元数据
     *
     * <p>完整流程：校验扩展名 → 存储 → 解析文本 → 写入元数据。
     * 解析失败不影响保存结果，仅 extracted_text 为空。
     *
     * @param userId   用户标识
     * @param content  文件字节
     * @param fileName 原始文件名（含扩展名）
     * @return 文件元数据（含 id）
     * @throws IllegalArgumentException 文件类型不支持或超出大小限制
     */
    public FileMetadata saveFile(String userId, byte[] content, String fileName) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }

        String extension = fileLocalStorage.extractExtension(fileName);
        if (!fileStorage.isAllowedExtension(extension)) {
            log.warn("不支持的文件类型，跳过保存 | userId={} | fileName={} | ext={}", userId, fileName, extension);
            throw new IllegalArgumentException("不支持的文件类型: ." + extension);
        }
        if (!fileStorage.isWithinSizeLimit(content)) {
            throw new IllegalArgumentException("文件超过大小限制");
        }

        // 1. 保存文件到存储层
        String storedName = fileStorage.save(userId, content, extension);

        // 2. 尝试提取文本（解析失败不阻止保存）
        String extractedText = extractText(content, fileName, extension);

        // 3. 注册元数据
        FileMetadata metadata = new FileMetadata();
        metadata.setUserId(userId);
        metadata.setFilename(fileName);
        metadata.setStoredName(storedName);
        metadata.setFileType(extension);
        metadata.setSize(content.length);
        metadata.setExtractedText(truncateText(extractedText, MAX_READ_LENGTH));
        metadata.setSource("user_upload");
        metadata.setStatus("active");

        metadataRepository.insert(metadata);

        log.info("文件保存完成 | userId={} | fileId={} | fileName={} | size={}",
                userId, metadata.getId(), fileName, content.length);
        return metadata;
    }

    // ==================== 读取 ====================

    /**
     * 读取文件内容文本
     *
     * <p>md/txt 文件直接读取 UTF-8 文本；
     * pdf/docx 通过 Tika 提取文本（从库中已保存的 extracted_text 返回）。
     *
     * @param userId 用户标识
     * @param fileId 文件 ID
     * @return 文件内容结果（含文本内容和截断标记），文件不存在返回 null
     */
    public FileContent readFileText(String userId, Long fileId) {
        FileMetadata metadata = metadataRepository.findByIdAndUserId(fileId, userId);
        if (metadata == null) {
            return null;
        }

        String text;
        if (TEXT_TYPES.contains(metadata.getFileType())) {
            // 文本类型：从磁盘读取
            byte[] bytes = fileStorage.read(userId, metadata.getStoredName());
            if (bytes == null) {
                return null;
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        } else {
            // 非文本类型：从元数据的 extracted_text 返回
            text = metadata.getExtractedText();
            if (text == null || text.isBlank()) {
                return new FileContent(metadata, "", false, "暂无法提取此文件内容");
            }
        }

        boolean truncated = text.length() > MAX_READ_LENGTH;
        if (truncated) {
            text = text.substring(0, MAX_READ_LENGTH);
        }

        return new FileContent(metadata, text, truncated,
                truncated ? "文件内容较长，仅显示前 " + MAX_READ_LENGTH + " 字符" : null);
    }

    /**
     * 文件内容结果
     *
     * @param metadata  文件元数据
     * @param content   文本内容（最多 5000 字符）
     * @param truncated 是否被截断
     * @param message   提示信息
     */
    public record FileContent(FileMetadata metadata, String content, boolean truncated, String message) {
    }

    // ==================== 搜索 ====================

    /**
     * 搜索用户文件（按文件名模糊匹配）
     *
     * @param userId   用户标识
     * @param keyword  搜索关键词
     * @param fileType 按类型过滤（可选，传 null 不过滤）
     * @param limit    返回数量上限（默认 10，最大 50）
     * @return 文件元数据列表（不含 extracted_text，减少数据传输）
     */
    public List<FileMetadata> searchFiles(String userId, String keyword, String fileType, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int maxLimit = Math.min(Math.max(limit, 1), 50);
        return metadataRepository.search(userId, keyword.trim(), fileType, maxLimit);
    }

    /**
     * 获取用户文件列表（无分页）
     *
     * @param userId 用户标识
     * @return 文件元数据列表
     */
    public List<FileMetadata> listFiles(String userId) {
        return metadataRepository.findByUserId(userId);
    }

    /**
     * 分页获取用户文件列表
     *
     * @param userId 用户标识
     * @param page   页码（从 1 开始）
     * @param size   每页数量（默认 20，最大 100）
     * @return 分页结果（含 total 和文件列表）
     */
    public FileListResult listFilesPaginated(String userId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * limit;
        int total = metadataRepository.countActiveByUserId(userId);
        List<FileMetadata> files = metadataRepository.findByUserIdPaginated(userId, limit, offset);
        return new FileListResult(files, total, page, limit);
    }

    /**
     * 分页文件列表结果
     *
     * @param files     当前页文件列表
     * @param total     文件总数
     * @param page      当前页码
     * @param pageSize  每页数量
     */
    public record FileListResult(List<FileMetadata> files, int total, int page, int pageSize) {
        public int totalPages() {
            return pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        }
    }

    /**
     * 删除文件（软删除）
     *
     * @param userId 用户标识
     * @param fileId 文件 ID
     * @return true 表示已删除
     */
    public boolean deleteFile(String userId, Long fileId) {
        return metadataRepository.softDelete(fileId, userId);
    }

    /**
     * 更新文件名（仅修改显示名，不影响存储文件）
     *
     * @param userId      用户标识
     * @param fileId      文件 ID
     * @param newFilename 新文件名
     * @return 更新后的文件元数据，文件不存在返回 null
     */
    public FileMetadata updateFileName(String userId, Long fileId, String newFilename) {
        String extension = fileLocalStorage.extractExtension(newFilename);
        if (!fileStorage.isAllowedExtension(extension)) {
            throw new IllegalArgumentException("不支持的文件类型: ." + extension);
        }

        boolean updated = metadataRepository.updateFilename(newFilename, fileId, userId);
        if (!updated) {
            return null;
        }
        return metadataRepository.findByIdAndUserId(fileId, userId);
    }

    /**
     * 保存文本内容为文件（用于 file_save Function）
     *
     * @param userId   用户标识
     * @param content  文本内容
     * @param fileName 文件名（含扩展名）
     * @return 文件元数据
     */
    public FileMetadata saveTextFile(String userId, String content, String fileName) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        FileMetadata metadata = saveFile(userId, bytes, fileName);
        // 通过 Agent 保存的文件标记来源
        metadataRepository.updateSource("agent_save", metadata.getId(), userId);
        metadata.setSource("agent_save");
        return metadata;
    }

    // ==================== 内部方法 ====================

    /**
     * 尝试使用 Tika 提取文件文本内容
     */
    private String extractText(byte[] content, String fileName, String extension) {
        try {
            if (TEXT_TYPES.contains(extension)) {
                // md/txt：直接读取
                return new String(content, StandardCharsets.UTF_8);
            }
            // pdf/docx：使用 Tika 提取
            FileParseService.FileParseResult result = fileParseService.parse(content, fileName);
            if (result != null && result.text() != null) {
                return result.text();
            }
        } catch (Exception e) {
            log.warn("文件文本提取失败 | fileName={} | ext={}", fileName, extension, e);
        }
        return "";
    }

    private String truncateText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}