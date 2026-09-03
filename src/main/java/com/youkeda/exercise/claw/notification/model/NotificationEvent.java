package com.youkeda.exercise.claw.notification.model;

/**
 * 统一通知事件。
 * 所有通知 Source 产出此事件，与推送通道完全解耦。
 * 未来可扩展 email/webpush 等通道，Source 无需修改。
 */
public class NotificationEvent {

    /** Source 标识，如 "ANIME"、"EXAM" */
    private String source;

    /** 通知标题，如 "咒术回战 第12集即将播出" */
    private String title;

    /** 通知正文 */
    private String content;

    /** 封面图 URL（可选） */
    private String coverUrl;

    /** 优先级 1-5，5 最高 */
    private int priority;

    /** 事件时间戳（Unix 秒） */
    private long timestamp;

    public NotificationEvent() {}

    public NotificationEvent(String source, String title, String content, String coverUrl, int priority, long timestamp) {
        this.source = source;
        this.title = title;
        this.content = content;
        this.coverUrl = coverUrl;
        this.priority = priority;
        this.timestamp = timestamp;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
