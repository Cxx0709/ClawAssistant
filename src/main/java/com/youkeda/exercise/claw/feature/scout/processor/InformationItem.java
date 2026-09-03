package com.youkeda.exercise.claw.feature.scout.processor;

import java.util.UUID;

/**
 * 统一信息结构
 *
 * 所有来源的信息统一为此格式
 */
public class InformationItem {

    private String id;
    private String title;
    private String content;
    private String source;
    private String sourceType;
    private String category;
    private long publishedAt;
    private long collectedAt;
    private String summary;
    private float[] vector;

    public InformationItem() {
        this.id = UUID.randomUUID().toString();
        this.collectedAt = System.currentTimeMillis();
    }

    public static InformationItem create(String title, String content,
                                          String source, String sourceType, String category) {
        InformationItem item = new InformationItem();
        item.title = title;
        item.content = content;
        item.source = source;
        item.sourceType = sourceType;
        item.category = category;
        return item;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getPublishedAt() { return publishedAt; }
    public void setPublishedAt(long publishedAt) { this.publishedAt = publishedAt; }

    public long getCollectedAt() { return collectedAt; }
    public void setCollectedAt(long collectedAt) { this.collectedAt = collectedAt; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }
}
