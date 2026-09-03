package com.youkeda.exercise.claw.domain.campus;

/**
 * 通用通知事件模型。
 * 统一表示一条"可通知的校园事件"，无论它来自考试/比赛/活动/就业。
 * source 字段区分来源，type 字段区分来源内部的子类型。
 */
public class NotificationItem {
    private Long id;
    private String source;          // EXAM / COMPETITION / ACTIVITY / JOB
    private String title;
    private String url;
    private String publishAt;
    private String content;
    private String type;            // 来源内部类型（FINAL_EXAM / CHALLENGE_CUP 等，String 避免强绑定枚举）
    private double confidence;
    private String scoreSource;     // RULE / LLM / HYBRID / NONE
    private String classifierReason;
    private String status;          // UNPROCESSED / CLASSIFIED / IGNORED
    private Long processedAt;
    private Long createdAt;

    public NotificationItem() {}

    public NotificationItem(String source, String title, String url, String publishAt) {
        this.source = source;
        this.title = title;
        this.url = url;
        this.publishAt = publishAt;
    }

    public boolean needsContent() {
        return content == null || content.isBlank();
    }

    // ==== getters / setters ====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPublishAt() { return publishAt; }
    public void setPublishAt(String publishAt) { this.publishAt = publishAt; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getScoreSource() { return scoreSource; }
    public void setScoreSource(String scoreSource) { this.scoreSource = scoreSource; }

    public String getClassifierReason() { return classifierReason; }
    public void setClassifierReason(String classifierReason) { this.classifierReason = classifierReason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getProcessedAt() { return processedAt; }
    public void setProcessedAt(Long processedAt) { this.processedAt = processedAt; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
