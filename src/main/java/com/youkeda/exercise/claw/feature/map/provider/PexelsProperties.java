package com.youkeda.exercise.claw.feature.map.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pexels API 配置 Properties
 *
 * <p>读取 application.properties 中的 pexels.api.key 和 pexels.base.url。
 * 通过 {@code place-image.provider=pexels} 启用 PexelsImageProvider。
 */
@Component
@ConfigurationProperties(prefix = "pexels")
public class PexelsProperties {

    /** Pexels API Key（从 https://www.pexels.com/api/ 获取） */
    private String apiKey;

    /** Pexels API 基础 URL */
    private String baseUrl = "https://api.pexels.com/v1";

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

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !"YOUR_PEXELS_API_KEY".equals(apiKey);
    }
}
