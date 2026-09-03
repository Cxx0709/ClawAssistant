package com.youkeda.exercise.claw.feature.map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 腾讯地图 API 配置
 *
 * <p>从 application.properties 读取 tencent.map.* 前缀的配置。
 * 用于腾讯位置服务（lbs.qq.com）的 API 调用鉴权。
 */
@Component
@ConfigurationProperties(prefix = "tencent")
public class TencentMapProperties {

    private static final Logger log = LoggerFactory.getLogger(TencentMapProperties.class);

    /**
     * 腾讯位置服务 API 密钥
     */
    private String key;

    @PostConstruct
    public void init() {
        if (key == null || key.isEmpty() || "YOUR_API_KEY_HERE".equals(key)) {
            log.warn("tencent.key 未配置或为默认值，地图功能将不可用");
        }
        log.info("腾讯地图配置加载完成");
    }

    /**
     * 获取 API 密钥
     */
    public String getApiKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}