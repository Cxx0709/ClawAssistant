package com.youkeda.exercise.claw.feature.map.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pexels 图片搜索 Provider（V2）
 *
 * <p>通过 Pexels API 获取真实地点照片并下载为字节数组。
 *
 * <p>V2 改进（相关性排序 + 低分截断）：
 * <ol>
 *   <li>每页取 15 张扩大候选池</li>
 *   <li>解析每张照片的 {@code alt} 文本</li>
 *   <li>按 alt、关键词、城市计算相关分数</li>
 *   <li>按分数降序排序，下载 Top N</li>
 *   <li>最高分低于阈值 → 返回空（不发送错误图片）</li>
 *   <li>英文关键词自动追加 "China" 帮助 Pexels 定位</li>
 * </ol>
 *
 * <p>通过 {@code place-image.provider=pexels} 启用。
 */
@Component
@ConditionalOnProperty(name = "place-image.provider", havingValue = "pexels", matchIfMissing = false)
public class PexelsImageProvider implements PlaceImageProvider {

    private static final Logger log = LoggerFactory.getLogger(PexelsImageProvider.class);

    /** Pexels API 每页返回数 */
    private static final int PER_PAGE = 15;

    /** 最大下载数 */
    private static final int MAX_DOWNLOAD = 3;

    /** 相关性最低阈值：低于此分不返回图片（避免发送错误图片） */
    private static final double RELEVANCE_THRESHOLD = 5.0;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /** 中文城市 → 英文名映射（用于 alt 文本匹配） */
    private static final Map<String, String> CITY_ENGLISH = Map.ofEntries(
            Map.entry("北京", "beijing"),
            Map.entry("上海", "shanghai"),
            Map.entry("杭州", "hangzhou"),
            Map.entry("南京", "nanjing"),
            Map.entry("成都", "chengdu"),
            Map.entry("广州", "guangzhou"),
            Map.entry("深圳", "shenzhen"),
            Map.entry("西安", "xi'an"),
            Map.entry("重庆", "chongqing"),
            Map.entry("苏州", "suzhou"),
            Map.entry("无锡", "wuxi"),
            Map.entry("武汉", "wuhan"),
            Map.entry("长沙", "changsha"),
            Map.entry("厦门", "xiamen"),
            Map.entry("青岛", "qingdao"),
            Map.entry("大连", "dalian"),
            Map.entry("昆明", "kunming"),
            Map.entry("丽江", "lijiang"),
            Map.entry("桂林", "guilin"),
            Map.entry("三亚", "sanya"),
            Map.entry("拉萨", "lhasa"),
            Map.entry("香港", "hong kong"),
            Map.entry("澳门", "macau"),
            Map.entry("天津", "tianjin"),
            Map.entry("哈尔滨", "harbin"),
            Map.entry("宁波", "ningbo"),
            Map.entry("福州", "fuzhou"),
            Map.entry("珠海", "zhuhai"),
            Map.entry("黄山", "huangshan"),
            Map.entry("敦煌", "dunhuang")
    );

    private final PexelsProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PexelsImageProvider(PexelsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<String> searchImages(String keyword, String city) {
        // 保留 URL 返回的兼容实现：先调用 API 获取 URL 列表
        if (!properties.isConfigured()) {
            log.warn("Pexels API Key 未配置，无法搜索图片");
            return List.of();
        }
        try {
            return searchPhotoUrls(keyword, city);
        } catch (Exception e) {
            log.error("Pexels URL 搜索失败 | keyword={} | city={} | error={}",
                    keyword, city, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<byte[]> searchImageBytes(String keyword, String city) {
        log.info("PexelsImageProvider | keyword={} | city={}", keyword, city);

        if (!properties.isConfigured()) {
            log.warn("Pexels API Key 未配置，无法搜索图片");
            return List.of();
        }

        try {
            // Step 1: 搜索候选照片（15 张）+ 计算相关性分数
            List<PhotoCandidate> candidates = searchPhotoCandidates(keyword, city);

            // Step 2: 按分数降序排序
            candidates.sort((a, b) -> Double.compare(b.score, a.score));

            // 日志：输出前 5 名候选
            if (log.isDebugEnabled()) {
                for (int i = 0; i < Math.min(candidates.size(), 5); i++) {
                    PhotoCandidate c = candidates.get(i);
                    log.debug("候选 #{} | score={} | alt={}", i + 1, c.score, truncateAlt(c.alt));
                }
            }

            // Step 3: 低分截断 —— 最高分低于阈值时不返回图片
            if (candidates.isEmpty() || candidates.get(0).score < RELEVANCE_THRESHOLD) {
                double best = candidates.isEmpty() ? 0 : candidates.get(0).score;
                log.info("Pexels 相关性过低，不返回图片 | keyword={} | city={} | candidates={} | bestScore={} | threshold={}",
                        keyword, city, candidates.size(), best, RELEVANCE_THRESHOLD);
                return List.of();
            }

            // Step 3b: 结构化日志 —— 输出完整评分信息
            double topScore = candidates.get(0).score;
            if (log.isInfoEnabled()) {
                List<PhotoCandidate> top5 = candidates.subList(0, Math.min(candidates.size(), 5));
                StringBuilder scoreBreakdown = new StringBuilder();
                scoreBreakdown.append("PexelsRank: keyword=").append(keyword)
                        .append(" | city=").append(city)
                        .append(" | candidates=").append(candidates.size())
                        .append(" | topScore=").append(String.format("%.1f", topScore))
                        .append(" | threshold=").append(RELEVANCE_THRESHOLD)
                        .append(" | sat=").append(topScore >= RELEVANCE_THRESHOLD ? "YES" : "NO");
                for (int i = 0; i < top5.size(); i++) {
                    PhotoCandidate c = top5.get(i);
                    scoreBreakdown.append(" | #").append(i + 1).append("=")
                            .append(String.format("%.1f", c.score))
                            .append(":").append(truncateAlt(c.alt));
                }
                log.info(scoreBreakdown.toString());
            }

            // Step 4: 下载 Top N
            List<byte[]> results = new ArrayList<>();
            for (PhotoCandidate c : candidates) {
                if (results.size() >= MAX_DOWNLOAD) break;
                try {
                    byte[] bytes = downloadPhoto(c.url);
                    if (bytes != null && bytes.length > 0) {
                        results.add(bytes);
                    }
                } catch (Exception e) {
                    log.debug("Pexels 图片下载失败，跳过 | url={}", c.url);
                }
            }

            log.info("PexelsImageProvider 完成 | keyword={} | city={} | candidates={} | downloaded={}",
                    keyword, city, candidates.size(), results.size());
            return results;

        } catch (Exception e) {
            log.error("PexelsImageProvider 搜索失败 | keyword={} | city={} | error={}",
                    keyword, city, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ==================== V2: 相关性排序 ====================

    /**
     * 搜索照片并计算相关性分数
     *
     * <p>增强策略：
     * <ul>
     *   <li>{@code per_page=15} 扩大候选池</li>
     *   <li>{@code city} 为英文时不再追加 "China"（已足够精确）</li>
     *   <li>{@code city} 为中文（未翻译）时追加 "China" 辅助 Pexels 定位</li>
     *   <li>解析每张照片的 alt 文本用于评分</li>
     * </ul>
     */
    private List<PhotoCandidate> searchPhotoCandidates(String keyword, String city) throws Exception {
        // 构造查询词
        String query = buildQuery(keyword, city);

        // 当城市仍是中文时（未翻译），追加 "China" 辅助 Pexels 定位
        // 城市已是英文时无需追加，如 "Tiananmen Square Beijing" 已足够精确
        if (city != null && !city.isBlank() && !isAscii(city)) {
            query = query + " China";
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = properties.getBaseUrl() + "/search?query=" + encodedQuery
                + "&per_page=" + PER_PAGE + "&orientation=landscape&size=medium";

        log.debug("Pexels 搜索请求 | query={} | per_page={}", query, PER_PAGE);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", properties.getApiKey())
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Pexels API HTTP {} | url={} | body={}",
                    response.statusCode(), url,
                    response.body().substring(0, Math.min(200, response.body().length())));
            return List.of();
        }

        List<PhotoCandidate> candidates = new ArrayList<>();
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode photos = root.path("photos");
        if (photos.isArray()) {
            for (JsonNode photo : photos) {
                String photoUrl = extractPhotoUrl(photo);
                if (photoUrl.isBlank()) continue;

                String alt = photo.path("alt").asText("");
                double score = calculateRelevance(alt, keyword, city);

                candidates.add(new PhotoCandidate(photoUrl, alt, score));
            }
        }

        log.debug("Pexels 搜索完成 | query={} | candidates={}", query, candidates.size());
        return candidates;
    }

    /**
     * 计算单张照片与查询词的相关性分数
     *
     * <p>评分维度：
     * <ul>
     *   <li>+5 — alt 包含英文查询词（精确短语匹配）</li>
     *   <li>+2 — 查询词中每个长词（>3 字符）在 alt 中出现</li>
     *   <li>+2 — alt 包含城市英文名</li>
     *   <li>+1 — alt 包含 "China"</li>
     * </ul>
     */
    private double calculateRelevance(String alt, String keyword, String city) {
        if (alt == null || alt.isBlank()) return 0;

        double score = 0;
        String altLower = alt.toLowerCase();
        String kwLower = keyword.toLowerCase();

        // (a) 精确短语匹配：alt 包含英文查询词
        if (altLower.contains(kwLower)) {
            score += 5;
        }

        // (b) 拆词匹配：查询词中每个长词（>3 字符）在 alt 中出现
        String[] kwWords = kwLower.replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        for (String word : kwWords) {
            word = word.trim();
            if (word.length() > 3 && altLower.contains(word)) {
                score += 2;
            }
        }

        // (c) 城市匹配：检查 alt 是否包含城市英文名
        if (city != null && !city.isBlank()) {
            String cityLower = city.toLowerCase().trim();
            // 先查英文城市名映射
            String engCity = CITY_ENGLISH.get(city);
            if (engCity != null && altLower.contains(engCity)) {
                score += 2;
            } else if (altLower.contains(cityLower)) {
                // 直接匹配中文城市名（意外情况，Pexels 较少见）
                score += 2;
            }
        }

        // (d) 国家匹配
        if (altLower.contains("china")) {
            score += 1;
        }

        return Math.max(0, score);
    }

    /**
     * 从 Pexels 照片节点中提取图片 URL
     */
    private String extractPhotoUrl(JsonNode photo) {
        String photoUrl = photo.path("src").path("large").asText("");
        if (photoUrl.isBlank()) {
            photoUrl = photo.path("src").path("original").asText("");
        }
        if (photoUrl.isBlank()) {
            photoUrl = photo.path("src").path("medium").asText("");
        }
        return photoUrl;
    }

    // ==================== 旧路径兼容（V1） ====================

    /**
     * 调用 Pexels Search API 获取照片 URL 列表（V1 兼容，仅返回 URL）
     */
    private List<String> searchPhotoUrls(String keyword, String city) throws Exception {
        String query = buildQuery(keyword, city);
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = properties.getBaseUrl() + "/search?query=" + encodedQuery
                + "&per_page=" + PER_PAGE + "&orientation=landscape&size=medium";

        log.debug("Pexels 搜索请求(V1) | query={} | url={}", query, url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", properties.getApiKey())
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Pexels API HTTP {} | url={} | body={}",
                    response.statusCode(), url,
                    response.body().substring(0, Math.min(200, response.body().length())));
            return List.of();
        }

        List<String> photoUrls = new ArrayList<>();
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode photos = root.path("photos");
        if (photos.isArray()) {
            for (JsonNode photo : photos) {
                String photoUrl = extractPhotoUrl(photo);
                if (!photoUrl.isBlank()) {
                    photoUrls.add(photoUrl);
                }
            }
        }

        log.debug("Pexels 搜索完成(V1) | query={} | urls={}", query, photoUrls.size());
        return photoUrls;
    }

    // ==================== 通用 ====================

    /**
     * 下载单张 Pexels 照片
     */
    private byte[] downloadPhoto(String photoUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(photoUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }
        return null;
    }

    /**
     * 构造搜索词：{keyword} {city}
     */
    private static String buildQuery(String keyword, String city) {
        StringBuilder sb = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(keyword);
        }
        if (city != null && !city.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(city);
        }
        return sb.toString().trim();
    }

    /**
     * 判断文本是否仅含 ASCII 字符（用于识别英文查询词）
     */
    private static boolean isAscii(String text) {
        if (text == null || text.isBlank()) return false;
        return text.chars().allMatch(c -> c < 128 || Character.isWhitespace(c));
    }

    /**
     * 截断 alt 文本（日志用）
     */
    private static String truncateAlt(String alt) {
        if (alt == null) return "";
        return alt.length() > 60 ? alt.substring(0, 60) + "..." : alt;
    }

    // ==================== Internal Data ====================

    /**
     * 照片候选：URL + alt 文本 + 相关分数
     */
    private record PhotoCandidate(String url, String alt, double score) {}
}
