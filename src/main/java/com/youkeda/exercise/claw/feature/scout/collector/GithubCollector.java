package com.youkeda.exercise.claw.feature.scout.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 信息采集器
 *
 * 采集内容：
 * 1. Trending 项目（按 Star 数排序的近期新建项目）
 * 2. 热门项目 Release 更新
 */
@Component
public class GithubCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(GithubCollector.class);
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GITHUB_SEARCH_API = "https://api.github.com/search/repositories";
    private static final int TIMEOUT_SECONDS = 15;

    private final ScoutProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GithubCollector(ScoutProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public String getType() {
        return "GITHUB";
    }

    @Override
    public List<InformationItem> collect(SearchTask task) {
        List<InformationItem> items = new ArrayList<>();

        try {
            // 搜索近 7 天内创建的高 Star 项目
            List<InformationItem> trending = searchTrending(task);
            items.addAll(trending);

            log.info("GitHub 采集完成 | query={} | trending={}", task.query(), trending.size());
        } catch (Exception e) {
            log.error("GitHub 采集失败 | query={}", task.query(), e);
        }

        return items;
    }

    /**
     * 搜索近期热门项目
     *
     * GitHub Search API：按 Star 数排序，筛选近 7 天创建的项目
     */
    private List<InformationItem> searchTrending(SearchTask task) throws Exception {
        // 构造查询：用户关键词 + 近 7 天创建
        String weekAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_DATE);
        String query = task.query() + " created:>" + weekAgo;

        String url = GITHUB_SEARCH_API + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&sort=stars&order=desc&per_page=" + Math.min(props.getMaxResultsPerTask(), 10);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "ClawBot-Scout/1.0")
                .GET()
                .build();

        // 采集失败重试（SSL/网络等瞬时错误），最多 2 次
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                log.warn("GitHub 采集失败（第 {}/2 次）| query={} | domain=api.github.com | error={}",
                        attempt, query, e.getMessage());
                if (attempt == 1) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }

        if (response == null) {
            throw new IllegalStateException("GitHub API 请求重试 2 次仍失败");
        }
        if (response.statusCode() != 200) {
            log.warn("GitHub API 请求失败 | status={} | body={}", response.statusCode(), response.body());
            return List.of();
        }

        return parseSearchResults(response.body(), task);
    }

    /**
     * 解析 GitHub Search API 响应
     */
    private List<InformationItem> parseSearchResults(String json, SearchTask task) {
        List<InformationItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode repos = root.get("items");
            if (repos == null || !repos.isArray()) return items;

            for (JsonNode repo : repos) {
                String fullName = safeText(repo, "full_name");
                String description = safeText(repo, "description");
                String htmlUrl = safeText(repo, "html_url");
                int stars = repo.has("stargazers_count") ? repo.get("stargazers_count").asInt() : 0;
                String language = safeText(repo, "language");
                String updatedAt = safeText(repo, "updated_at");

                // 构造标题和内容
                String title = "🔥 " + fullName + " (⭐" + stars + ")";
                StringBuilder content = new StringBuilder();
                if (!description.isEmpty()) {
                    content.append(description);
                }
                if (!language.isEmpty()) {
                    content.append(" | 语言：").append(language);
                }

                InformationItem item = InformationItem.create(
                        title,
                        content.toString(),
                        htmlUrl,
                        getType(),
                        task.category()
                );

                // 解析更新时间
                if (!updatedAt.isEmpty()) {
                    try {
                        item.setPublishedAt(java.time.Instant.parse(updatedAt).toEpochMilli());
                    } catch (Exception ignored) {}
                }

                items.add(item);
            }
        } catch (Exception e) {
            log.error("GitHub 搜索结果解析失败", e);
        }

        return items;
    }

    private static String safeText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}
