package com.youkeda.exercise.claw.domain.file;

/**
 * 文件元数据实体
 *
 * <p>记录用户已保存文件的元信息。以 {@code userId} 作为隔离键，
 * 确保不同用户的文件数据完全隔离。
 *
 * <p>持久化到 SQLite {@code file_metadata} 表。
 */
public class FileMetadata {

    private Long id;
    private String userId;
    /** 原始文件名，如 "操作系统学习笔记.md" */
    private String filename;
    /** 存储文件名，如 "a1b2c3d4-操作系统学习笔记.md" */
    private String storedName;
    /** 文件类型/扩展名，如 "md", "pdf" */
    private String fileType;
    /** 文件大小（字节） */
    private long size;
    /** 分类标签（预留） */
    private String category;
    /** Tika 提取的文本内容（最长 5000 字符） */
    private String extractedText;
    /** LLM 摘要（预留） */
    private String summary;
    /** 文件来源：user_upload / agent_save（预留，未来追踪文件来源） */
    private String source;
    /** 状态：active / deleted */
    private String status;
    /** 创建时间 */
    private String createdTime;

    public FileMetadata() {
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
}