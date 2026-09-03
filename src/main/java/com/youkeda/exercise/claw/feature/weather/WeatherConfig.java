package com.youkeda.exercise.claw.feature.weather;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 天气 API 配置
 * 从 application.properties 读取 weather.api.* 前缀的配置（WeatherAPI.com）
 */
@Component
@ConfigurationProperties(prefix = "weather.api")
public class WeatherConfig {

    /**
     * API 密钥
     */
    private String key;

    /**
     * API 请求 URL 模板，支持 {key} 和 {city} 占位符
     */
    private String url;

    private static final Logger log = LoggerFactory.getLogger(WeatherConfig.class);

    @PostConstruct
    public void init() {
        if (key == null || key.isEmpty() || "YOUR_API_KEY_HERE".equals(key)) {
            log.warn("weather.api.key 未配置或为默认值，天气功能将不可用");
        }
        if (url == null || url.isEmpty()) {
            log.warn("weather.api.url 未配置，天气功能将不可用");
        }
        log.info("天气配置加载完成");
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}