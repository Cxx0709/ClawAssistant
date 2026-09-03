package com.youkeda.exercise.claw.feature.transport.didi;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 滴滴 MCP Server 配置
 *
 * <p>从 application.properties 读取 didi.mcp.* 前缀的配置。
 * 用于滴滴出行 MCP Server（JSON-RPC 2.0）的连接鉴权。
 *
 * <p>接入地址说明：
 * <ul>
 *   <li><b>沙箱环境</b>：https://mcp.didichuxing.com/mcp-servers-sandbox?key=API_KEY（Mock 数据）</li>
 *   <li><b>生产环境</b>：https://mcp.didichuxing.com/mcp-servers?key=API_KEY（真实订单）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "didi.mcp")
public class DidiMcpProperties {

    private static final Logger log = LoggerFactory.getLogger(DidiMcpProperties.class);

    /** MCP Server 基础地址（不含 key 查询参数） */
    private String baseUrl = "https://mcp.didichuxing.com/mcp-servers-sandbox";

    /** API Key（URL 查询参数方式传递） */
    private String apiKey = "";

    /** HTTP 超时时间（毫秒） */
    private int timeout = 15000;

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.isBlank() || "YOUR_MCP_KEY".equals(apiKey)) {
            log.warn("didi.mcp.api-key 未配置或为默认值，滴滴打车功能将不可用");
        }
        log.info("滴滴 MCP 配置加载完成 | baseUrl={} | timeout={}ms", baseUrl, timeout);
    }

    // ==================== Getters & Setters ====================

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
}
