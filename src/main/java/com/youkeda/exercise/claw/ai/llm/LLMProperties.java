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
    private String baseUrl = "https://api.xiaomimimo.com/v1";

    /**
     * 模型名称
     */
    private String model = "mimo-v2.5-pro";

    /**
     * 流式响应连续多久没有收到任何一行数据后主动断开。
     *
     * <p>{@link java.net.http.HttpRequest.Builder#timeout(java.time.Duration)} 在使用
     * {@code BodyHandlers.ofInputStream()} 时只覆盖响应头阶段，因此响应体断流需要单独的空闲超时。
     */
    private int streamIdleTimeoutSeconds = 30;

    /**
     * 是否禁用流式调用。当底层模型流式输出不稳定时（如返回空正文），
     * 设置为 true 可跳过流式直接走全量调用，避免重复推送。
     */
    private boolean streamDisabled = false;

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

    public int getStreamIdleTimeoutSeconds() {
        return streamIdleTimeoutSeconds;
    }

    public void setStreamIdleTimeoutSeconds(int streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
    }

    public boolean isStreamDisabled() {
        return streamDisabled;
    }

    public void setStreamDisabled(boolean streamDisabled) {
        this.streamDisabled = streamDisabled;
    }
}
