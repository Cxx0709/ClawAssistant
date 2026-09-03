package com.youkeda.exercise.claw.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置属性
 *
 * 从 application.properties 读取 llm.* 前缀的配置
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LLMProperties {

    /**
     * API 密钥。
     *
     * <p>P0-5 标记：当前明文写在 application.properties 且已提交 git 历史。
     * 应从环境变量注入（如 {@code ${DASHSCOPE_API_KEY:}}），git 历史清理留待后续（见 design-p0-rework-2026-08-01.md §5）。
     */
    private String apiKey;

    /**
     * API 基础地址
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * 模型名称
     */
    private String model = "qwen3.6-plus";

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
