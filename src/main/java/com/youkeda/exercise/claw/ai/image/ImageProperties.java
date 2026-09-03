package com.youkeda.exercise.claw.ai.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片生成模型配置属性
 *
 * 从 application.properties 读取 image.* 前缀的配置
 */
@Component
@ConfigurationProperties(prefix = "image")
public class ImageProperties {

    /**
     * API 密钥（可复用 llm 或 vision 的密钥，也可独立配置）
     */
    private String apiKey;

    /**
     * API 基础地址（默认阿里云 DashScope）
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /**
     * 图片生成模型名称
     */
    private String model = "qwen-image-2.0";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}