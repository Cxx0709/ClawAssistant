package com.youkeda.exercise.claw.infrastructure.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件解析配置属性
 *
 * 从 application.properties 读取 file.parse.* 前缀的配置
 */
@Component
@ConfigurationProperties(prefix = "file.parse")
public class FileParseProperties {

    /**
     * 允许解析的最大文件大小（字节），超过此大小返回错误提示
     */
    private long maxFileSize = 10 * 1024 * 1024; // 10MB

    /**
     * 从文件中提取的最大文本长度（字符数），超过此长度截断
     */
    private int maxTextLength = 30_000;

    /**
     * 从 OOXML 文档（DOCX/PPTX）中提取内嵌图片的最大数量
     */
    private int maxEmbeddedImages = 5;

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public int getMaxEmbeddedImages() {
        return maxEmbeddedImages;
    }

    public void setMaxEmbeddedImages(int maxEmbeddedImages) {
        this.maxEmbeddedImages = maxEmbeddedImages;
    }
}