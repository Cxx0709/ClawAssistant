package com.youkeda.exercise.claw.feature.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * Tavily 搜索服务
 *
 * <p>封装 Tavily Search API 调用。Tavily 专为 AI Agent 设计：
 * <ul>
 *   <li>返回 LLM 友好的结构化结果（title / url / content / score）</li>
 *   <li>内置 AI 摘要（answer 字段），LLM 可直接引用</li>
 *   <li>每个结果有相关性评分，方便 LLM 筛选</li>
 * </ul>
 *
 * <p>定位：当 LLM 发现已有专业工具无法满足的信息需求时调用。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final WebSearchConfig config;
    private final ObjectMapper objectMapper;

    public SearchService(WebSearchConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行搜索（使用配置的默认结果数）
     *
     * @param query 搜索关键词
     * @return 搜索结果 JSON（含 answer 摘要 + results 列表），失败返回 error JSON
     */
    public String search(String query) {
        return search(query, config.getMaxResults());
    }

    /**
     * 执行搜索（指定返回条数）
     *
     * @param query      搜索关键词
     * @param maxResults 返回结果条数（1-20）
     * @return 搜索结果 JSON
     */
    public String search(String query, int maxResults) {
        try {
            // 1. 构建 POST JSON 请求体
            ObjectNode body = objectMapper.createObjectNode();
            body.put("api_key", config.getKey());
            body.put("query", query);
            body.put("search_depth", config.getSearchDepth());
            body.put("max_results", Math.min(maxResults, 20));
            body.put("include_answer", config.getIncludeAnswer());
            body.put("include_raw_content", false);
            body.put("include_images", false);

            String requestBody = objectMapper.writeValueAsString(body);
            log.info("Tavily 搜索 | query={} | maxResults={}", query, maxResults);

            // 2. POST 请求
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Tavily API 异常 | status={} | body={}", response.statusCode(), response.body());
                return "{\"error\": \"搜索服务返回 HTTP " + response.statusCode() + "\"}";
            }

            // 3. 精简响应，只保留 LLM 需要的字段
            return formatResponse(response.body(), query);

        } catch (Exception e) {
            log.error("搜索失败 | query={} | error={}", query, e.getMessage());
            return "{\"error\": \"搜索服务不可用: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 按时间排序搜索（信息猎手专用）
     *
     * @param query      搜索关键词
     * @param maxResults 返回结果条数
     * @return 搜索结果 JSON，按发布时间排序
     */
    public String searchByDate(String query, int maxResults) {
        return searchByDate(query, maxResults, 0);
    }

    /**
     * 按时间搜索，并限定最近天数。
     */
    public String searchByDate(String query, int maxResults, int freshnessDays) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("api_key", config.getKey());
            body.put("query", query);
            body.put("search_depth", config.getSearchDepth());
            body.put("max_results", Math.min(maxResults, 20));
            body.put("include_answer", config.getIncludeAnswer());
            body.put("include_raw_content", false);
            body.put("include_images", false);
            body.put("sort_by", "date"); // 按时间排序
            if (freshnessDays > 0) {
                body.put("topic", "news");
                body.put("days", freshnessDays);
            }

            String requestBody = objectMapper.writeValueAsString(body);
            log.info("Tavily 搜索（按时间）| query={} | maxResults={}", query, maxResults);

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Tavily API 异常 | status={}", response.statusCode());
                return "{\"error\": \"搜索服务返回 HTTP " + response.statusCode() + "\"}";
            }

            return formatResponse(response.body(), query);

        } catch (Exception e) {
            log.error("搜索失败（按时间）| query={} | error={}", query, e.getMessage());
            return "{\"error\": \"搜索服务不可用: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 从 Tavily 原始响应中提取关键字段，精简后返回给 LLM
     *
     * <p>Tavily 原始响应包含很多字段（images、response_time 等），LLM 不需要全部。
     * 这里只保留 answer（AI 摘要）和 results（title/url/content/score）。
     */
    String formatResponse(String rawJson, String query) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("query", query);

            // AI 摘要 — Tavily 已做好总结，LLM 可以直接用
            String answer = root.has("answer") ? root.get("answer").asText() : null;
            if (answer != null && !answer.isEmpty()) {
                out.put("answer", answer);
            }

            // 搜索结果列表 — 保留 title / url / content(截断) / score
            ArrayNode results = out.putArray("results");
            JsonNode tavilyResults = root.get("results");
            if (tavilyResults != null && tavilyResults.isArray()) {
                for (JsonNode r : tavilyResults) {
                    ObjectNode item = results.addObject();
                    item.put("title", safeText(r, "title"));
                    item.put("url", safeText(r, "url"));
                    // 截断 content，减少 token 消耗
                    String content = safeText(r, "content");
                    if (content.length() > 300) {
                        content = content.substring(0, 300) + "...";
                    }
                    item.put("content", content);
                    if (r.has("score")) {
                        item.put("score", r.get("score").asDouble());
                    }
                    String publishedDate = safeText(r, "published_date");
                    if (!publishedDate.isBlank()) {
                        item.put("published_date", publishedDate);
                    }
                }
            }

            String formatted = objectMapper.writeValueAsString(out);
            log.debug("Tavily 响应格式化完成 | results={}", results.size());
            return formatted;

        } catch (Exception e) {
            log.error("格式化 Tavily 响应失败 | error={}", e.getMessage());
            return "{\"error\": \"解析搜索结果失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String safeText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}
