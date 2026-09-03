package com.youkeda.exercise.claw.ai.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视觉模型配置属性
 *
 * 从 application.properties 读取 vision.* 前缀的配置
 * 与文本 LLM 配置独立，支持不同的模型提供商
 */
@Component
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * API 基础地址（默认阿里云 DashScope，与 voice/image 同厂商）
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * 视觉模型名称
     */
    private String model = "qwen-vl-max";

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