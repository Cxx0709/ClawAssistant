package com.youkeda.exercise.claw.feature.websearch;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tavily 搜索 API 配置
 *
 * <p>Tavily 是专为 AI Agent 设计的搜索引擎，返回结构化结果 + LLM 摘要 + 来源引用。
 * 从 application.properties 读取 websearch.api.* 前缀的配置。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "websearch.api")
public class WebSearchConfig {

    /** API 密钥（https://app.tavily.com 获取） */
    private String key;

    /** Tavily API 地址 */
    private String url = "https://api.tavily.com/search";

    /** 搜索深度：basic（快速）或 advanced（深入，结果更多更准） */
    private String searchDepth = "basic";

    /** 返回结果条数，默认 5，最大 20 */
    private int maxResults = 5;

    /** 是否包含 AI 摘要：basic（简短）或 advanced（详细） */
    private String includeAnswer = "basic";

    /** HTTP 请求超时（秒），默认 15 */
    private int timeout = 15;

    @PostConstruct
    public void init() {
        if (key == null || key.isEmpty() || "YOUR_API_KEY_HERE".equals(key)) {
            log.warn("websearch.api.key 未配置或为默认值，搜索功能不可用");
        }
        log.info("Tavily 搜索配置加载完成 | searchDepth={} | maxResults={}", searchDepth, maxResults);
    }
}
