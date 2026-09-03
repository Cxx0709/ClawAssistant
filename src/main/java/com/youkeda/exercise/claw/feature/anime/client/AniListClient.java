package com.youkeda.exercise.claw.feature.anime.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.domain.anime.AnimeEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AniList GraphQL 客户端。
 * <p>
 * 通过 AniList GraphQL API 搜索番剧、获取当季新番和播出时间表。
 * 仅在配置 {@code anime.enabled=true} 时生效。
 */
@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class AniListClient {

    private static final Logger log = LoggerFactory.getLogger(AniListClient.class);
    private static final String GRAPHQL_URL = "https://graphql.anilist.co";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 搜索番剧。
     *
     * @param keyword 搜索关键词
     * @return 匹配的番剧列表，最多 10 条；异常时返回空列表
     */
    public List<Anime> searchAnime(String keyword) {
        String query = """
                query ($search: String) {
                    Page(page: 1, perPage: 10) {
                        media(search: $search, type: ANIME) {
                            id
                            title { romaji native english }
                            coverImage { large }
                            status
                            episodes
                            genres
                        }
                    }
                }
                """;
        try {
            JsonNode data = executeQuery(query, Map.of("search", keyword));
            if (data == null) {
                return List.of();
            }
            JsonNode mediaArray = data.path("Page").path("media");
            return parseAnimeList(mediaArray);
        } catch (Exception e) {
            log.error("搜索番剧失败，keyword={}", keyword, e);
            return List.of();
        }
    }

    /**
     * 获取当前季度（东京时区）。
     *
     * @return 季度字符串：WINTER / SPRING / SUMMER / FALL
     */
    public static String getCurrentSeason() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        int month = now.getMonthValue();
        if (month <= 3) {
            return "WINTER";
        } else if (month <= 6) {
            return "SPRING";
        } else if (month <= 9) {
            return "SUMMER";
        } else {
            return "FALL";
        }
    }

    /**
     * 获取当季新番。
     *
     * @param page 分页页码
     * @return 当季番剧列表，按热度降序排列；异常时返回空列表
     */
    public List<Anime> getCurrentSeasonAnime(int page) {
        String season = getCurrentSeason();
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Tokyo"));

        String query = """
                query ($season: MediaSeason, $year: Int, $page: Int) {
                    Page(page: $page, perPage: 50) {
                        media(season: $season, seasonYear: $year, type: ANIME, sort: POPULARITY_DESC) {
                            id
                            title { romaji native english }
                            coverImage { large }
                            status
                            episodes
                            genres
                            averageScore
                            popularity
                        }
                    }
                }
                """;
        try {
            JsonNode data = executeQuery(query, Map.of("season", season, "year", now.getYear(), "page", page));
            if (data == null) {
                return List.of();
            }
            JsonNode mediaArray = data.path("Page").path("media");
            return parseAnimeList(mediaArray);
        } catch (Exception e) {
            log.error("获取当季新番失败", e);
            return List.of();
        }
    }

    /**
     * 获取指定番剧的最新一集播出信息。
     *
     * @param anilistId AniList ID
     * @return 最新一集的播出信息；如果番剧尚未安排播出或异常时返回 {@code null}
     */
    public AnimeEpisode getAiringSchedule(int anilistId) {
        String query = """
                query ($id: Int) {
                    Media(id: $id) {
                        nextAiringEpisode {
                            episode
                            airingAt
                        }
                    }
                }
                """;
        try {
            JsonNode data = executeQuery(query, Map.of("id", anilistId));
            if (data == null) {
                return null;
            }

            JsonNode mediaNode = data.path("Media");
            if (mediaNode.isMissingNode()) {
                return null;
            }

            JsonNode nextAiringEpisode = mediaNode.path("nextAiringEpisode");
            if (nextAiringEpisode.isMissingNode() || nextAiringEpisode.isNull()) {
                return null;
            }

            int episode = nextAiringEpisode.path("episode").asInt();
            long airingAt = nextAiringEpisode.path("airingAt").asLong();
            return new AnimeEpisode(anilistId, episode, airingAt);
        } catch (Exception e) {
            log.error("获取播出信息失败，anilistId={}", anilistId, e);
            return null;
        }
    }

    /**
     * 按 AniList ID 直接查询番剧详情。
     * 相比按名称搜索，ID 查询更精确、不受语言限制。
     *
     * @param anilistId AniList ID
     * @return 番剧信息；未找到或异常时返回 {@code null}
     */
    public Anime getAnimeById(int anilistId) {
        String query = """
                query ($id: Int) {
                    Media(id: $id, type: ANIME) {
                        id
                        title { romaji native english }
                        coverImage { large }
                        status
                        episodes
                        genres
                        averageScore
                        popularity
                    }
                }
                """;
        try {
            JsonNode data = executeQuery(query, Map.of("id", anilistId));
            if (data == null) {
                return null;
            }

            JsonNode mediaNode = data.path("Media");
            if (mediaNode.isMissingNode() || mediaNode.isNull()) {
                return null;
            }

            return parseSingleAnime(mediaNode);
        } catch (Exception e) {
            log.error("按 ID 查询番剧失败，anilistId={}", anilistId, e);
            return null;
        }
    }

    /**
     * 执行 GraphQL 查询的通用方法。
     * <p>
     * 向 AniList GraphQL 端点发送 POST 请求，返回 {@code data} 节点。
     * 如果请求失败或 API 返回错误，则记录日志并返回 {@code null}。
     *
     * @param query     GraphQL 查询字符串
     * @param variables 查询变量
     * @return response 中的 {@code data} 节点，失败时返回 {@code null}
     */
    private JsonNode executeQuery(String query, Map<String, Object> variables) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = Map.of(
                    "query", query,
                    "variables", variables
            );
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            JsonNode response = restTemplate.postForObject(GRAPHQL_URL, request, JsonNode.class);
            if (response == null) {
                log.warn("AniList API 返回空响应");
                return null;
            }

            // 检查是否有 GraphQL 错误
            JsonNode errors = response.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && errors.size() > 0) {
                log.warn("AniList GraphQL 返回错误: {}", errors);
                return null;
            }

            return response.path("data");
        } catch (Exception e) {
            log.error("GraphQL 查询请求异常", e);
            return null;
        }
    }

    /**
     * 将 AniList media 数组节点解析为 {@link Anime} 列表。
     *
     * @param mediaArray {@code data.Page.media} 节点
     * @return Anime 列表，不会返回 {@code null}
     */
    private List<Anime> parseAnimeList(JsonNode mediaArray) {
        List<Anime> list = new ArrayList<>();
        if (mediaArray == null || mediaArray.isMissingNode() || !mediaArray.isArray()) {
            return list;
        }
        for (JsonNode node : mediaArray) {
            Anime anime = new Anime();
            anime.setAnilistId(node.path("id").asInt());

            // title 嵌套对象：{ romaji, native, english }
            JsonNode titleNode = node.path("title");
            anime.setTitle(safeText(titleNode.path("romaji")));
            anime.setTitleJa(safeText(titleNode.path("native")));

            // coverImage 嵌套对象：{ large }
            anime.setCoverUrl(safeText(node.path("coverImage").path("large")));

            // 标量字段
            anime.setStatus(safeText(node.path("status")));
            anime.setEpisodeCount(node.path("episodes").asInt(0));
            anime.setAverageScore(node.path("averageScore").asInt(0));
            anime.setPopularity(node.path("popularity").asInt(0));

            // genres 数组
            List<String> genres = new ArrayList<>();
            JsonNode genresNode = node.path("genres");
            if (genresNode.isArray()) {
                for (JsonNode g : genresNode) {
                    genres.add(g.asText());
                }
            }
            anime.setGenres(genres);

            list.add(anime);
        }
        return list;
    }

    /**
     * 将 AniList media 单节点解析为 {@link Anime} 对象。
     *
     * @param node {@code data.Media} 节点
     * @return Anime 对象；节点无效时返回 {@code null}
     */
    private Anime parseSingleAnime(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Anime anime = new Anime();
        anime.setAnilistId(node.path("id").asInt());

        // title 嵌套对象：{ romaji, native, english }
        JsonNode titleNode = node.path("title");
        anime.setTitle(safeText(titleNode.path("romaji")));
        anime.setTitleJa(safeText(titleNode.path("native")));

        // coverImage 嵌套对象：{ large }
        anime.setCoverUrl(safeText(node.path("coverImage").path("large")));

        // 标量字段
        anime.setStatus(safeText(node.path("status")));
        anime.setEpisodeCount(node.path("episodes").asInt(0));
        anime.setAverageScore(node.path("averageScore").asInt(0));
        anime.setPopularity(node.path("popularity").asInt(0));

        // genres 数组
        List<String> genres = new ArrayList<>();
        JsonNode genresNode = node.path("genres");
        if (genresNode.isArray()) {
            for (JsonNode g : genresNode) {
                genres.add(g.asText());
            }
        }
        anime.setGenres(genres);

        return anime;
    }

    /**
     * 安全地获取 JsonNode 的文本值，缺失或 null 时返回 {@code null}。
     */
    private static String safeText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }
}
