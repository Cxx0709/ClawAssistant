package com.youkeda.exercise.claw.tool.anime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.anime.client.AniListClient;
import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.domain.anime.AnimeEpisode;
import com.youkeda.exercise.claw.feature.anime.store.AnimeSubscriptionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class AnimeSubscribeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnimeSubscribeTool.class);

    private final AniListClient aniListClient;
    private final AnimeSubscriptionStore subscriptionStore;

    public AnimeSubscribeTool(AniListClient aniListClient,
                                  AnimeSubscriptionStore subscriptionStore,
                                  ToolRegistry functionRegistry,
                                  ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.aniListClient = aniListClient;
        this.subscriptionStore = subscriptionStore;
    }

    @Override
    public String getName() { return "anime_subscribe"; }

    @Override
    public String getDescription() {
        return "管理追番列表。支持搜索番剧、订阅、取消订阅、查看列表、查询播出/更新时间。"
            + "仅在用户明确要求追番、订阅、取消订阅、查看订阅列表或查询更新时间时调用；单纯推荐新番不要调用。"
            + "当用户已从推荐列表中指定番剧时，优先使用 animeId 参数进行 subscribe 操作。"
            + "当用户问某部番剧'什么时候更新'、'几点播出'、'第几集什么时候出'时，使用 schedule 动作。"
            + "当用户说'帮我追番'、'订阅'、'取消追番'、'我追的番'时调用。";
    }

    @Override
    public boolean isAvailable(ToolExecutionContext context) {
        String message = context == null ? "" : context.currentMessage();
        if (message == null || message.isBlank()) return true;

        boolean recommendation = message.matches("(?s).*(推荐|好看|这季|新番).*");
        boolean subscriptionAction = message.matches("(?s).*(订阅|追番|取消追番|已订阅|订阅列表|追番列表|什么时候更新|何时更新|播出时间|几点播出).*");
        return !recommendation || subscriptionAction;
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：search=搜索番剧, subscribe=订阅, unsubscribe=取消订阅, list=查看列表, schedule=查询播出/更新时间");
        action.set("enum", objectMapper.createArrayNode()
            .add("search").add("subscribe").add("unsubscribe").add("list").add("schedule"));

        return schema()
                .raw("action", action, true)
                .string("animeName", "番剧名称（search/schedule 时需要；subscribe/unsubscribe 时若提供 animeId 则非必填。注意：AniList 不支持中文搜索，请使用英文或罗马音标题搜索）")
                .number("animeId", "AniList 番剧 ID（subscribe 时推荐使用，比按名称搜索更准确，来自推荐列表中的 id 字段）")
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = args.path("action").asText("list");

            return switch (action) {
                case "search" -> handleSearch(args);
                case "subscribe" -> handleSubscribe(args);
                case "unsubscribe" -> handleUnsubscribe(args);
                case "list" -> handleList();
                case "schedule" -> handleSchedule(args);
                default -> "{\"status\":\"ERROR\",\"message\":\"未知操作: " + action + "\"}";
            };
        } catch (Exception e) {
            log.error("AnimeSubscribeTool 执行失败", e);
            return "{\"status\":\"ERROR\",\"message\":\"执行失败: " + e.getMessage() + "\"}";
        }
    }

    private String handleSearch(JsonNode args) throws Exception {
        String keyword = args.path("animeName").asText("");
        if (keyword.isBlank()) {
            return "{\"status\":\"ERROR\",\"message\":\"请提供要搜索的番剧名称\"}";
        }
        List<Anime> results = aniListClient.searchAnime(keyword);
        if (results.isEmpty()) {
            return "{\"status\":\"SUCCESS\",\"message\":\"未找到与「" + keyword + "」相关的番剧\"}";
        }
        // 格式化搜索结果返回给 LLM
        return objectMapper.writeValueAsString(Map.of(
            "status", "SUCCESS",
            "results", results.stream().map(a -> Map.of(
                "id", a.getAnilistId(),
                "title", a.getTitle(),
                "status", a.getStatus()
            )).toList()
        ));
    }

    private String handleSubscribe(JsonNode args) throws Exception {
        int animeId = args.path("animeId").asInt(0);
        String name = args.path("animeName").asText("");

        Anime target = null;

        // 优先按 ID 直接查询（推荐列表返回了 id，LLM 应当使用此路径）
        if (animeId > 0) {
            target = aniListClient.getAnimeById(animeId);
            if (target == null) {
                return "{\"status\":\"ERROR\",\"message\":\"未找到 ID 为 " + animeId + " 的番剧\"}";
            }
        } else if (!name.isBlank()) {
            // 兜底：按名称搜索
            List<Anime> results = aniListClient.searchAnime(name);
            if (results.isEmpty()) {
                return "{\"status\":\"SUCCESS\",\"message\":\"未找到与「" + name + "」相关的番剧\"}";
            }
            target = results.get(0);
        } else {
            return "{\"status\":\"ERROR\",\"message\":\"请提供番剧 ID 或名称\"}";
        }

        subscriptionStore.subscribe(target);
        log.info("用户订阅了番剧 | title={} | id={}", target.getTitle(), target.getAnilistId());
        return "{\"status\":\"SUCCESS\",\"message\":\"已订阅《" + target.getTitle() + "》！播出前会提醒你。\"}";
    }

    private String handleUnsubscribe(JsonNode args) throws Exception {
        int animeId = args.path("animeId").asInt(0);
        String name = args.path("animeName").asText("");
        if (animeId > 0) {
            subscriptionStore.unsubscribe(animeId);
            return "{\"status\":\"SUCCESS\",\"message\":\"已取消订阅\"}";
        }
        // 按名称取消
        Anime subscribed = subscriptionStore.listAll().stream()
            .filter(a -> a.getTitle().contains(name))
            .findFirst().orElse(null);
        if (subscribed == null) {
            return "{\"status\":\"ERROR\",\"message\":\"未找到匹配的番剧\"}";
        }
        subscriptionStore.unsubscribe(subscribed.getAnilistId());
        return "{\"status\":\"SUCCESS\",\"message\":\"已取消订阅《" + subscribed.getTitle() + "》\"}";
    }

    private String handleList() throws Exception {
        List<Anime> list = subscriptionStore.listAll();
        if (list.isEmpty()) {
            return "{\"status\":\"SUCCESS\",\"message\":\"你还没有追任何番剧。说'帮我追咒术回战'来开始追番！\"}";
        }
        return objectMapper.writeValueAsString(Map.of(
            "status", "SUCCESS",
            "subscriptions", list.stream().map(a -> Map.of(
                "title", a.getTitle(),
                "status", a.getStatus(),
                "id", a.getAnilistId()
            )).toList()
        ));
    }

    private String handleSchedule(JsonNode args) throws Exception {
        int animeId = args.path("animeId").asInt(0);
        String name = args.path("animeName").asText("");

        Anime target;

        // 优先按 ID 直接查询（比名称搜索更精确，来自推荐/搜索结果中的 id 字段）
        if (animeId > 0) {
            target = aniListClient.getAnimeById(animeId);
            if (target == null) {
                return "{\"status\":\"ERROR\",\"message\":\"未找到 ID 为 " + animeId + " 的番剧\"}";
            }
        } else if (!name.isBlank()) {
            List<Anime> results = aniListClient.searchAnime(name);
            if (results.isEmpty()) {
                return "{\"status\":\"SUCCESS\",\"message\":\"未找到与「" + name + "」相关的番剧\"}";
            }
            // 优先选择正在播出或未播出的条目（已完结的番无排期）
            target = results.stream()
                .filter(a -> "RELEASING".equals(a.getStatus()) || "NOT_YET_RELEASED".equals(a.getStatus()))
                .findFirst()
                .orElse(results.get(0));
        } else {
            return "{\"status\":\"ERROR\",\"message\":\"请提供番剧 ID 或名称\"}";
        }

        AnimeEpisode episode = aniListClient.getAiringSchedule(target.getAnilistId());
        if (episode == null) {
            return "{\"status\":\"SUCCESS\",\"message\":\"《" + target.getTitle() + "》暂无排期信息（可能已完结或未定档）\"}";
        }

        // Unix 秒 → 北京时间
        LocalDateTime airTime = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(episode.getAiringAt()), ZoneId.of("Asia/Shanghai"));
        String formatted = airTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        return "{\"status\":\"SUCCESS\",\"message\":\"《" + target.getTitle() + "》第 "
            + episode.getEpisode() + " 集将于 " + formatted + "（北京时间）播出\"}";
    }
}
