package com.youkeda.exercise.claw.feature.anime.notification;
import com.youkeda.exercise.claw.notification.NotificationSource;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.anime.client.AniListClient;
import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.feature.anime.store.AnimeSubscriptionStore;

import com.youkeda.exercise.claw.notification.NotificationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class AnimeSeasonSource implements NotificationSource {

    private static final Logger log = LoggerFactory.getLogger(AnimeSeasonSource.class);

    private final AniListClient aniListClient;
    private final AnimeSubscriptionStore subscriptionStore;
    private final NotificationEventPublisher publisher;
    private final LLMClient llmClient;

    @Value("${anime.seasonRecommendation.enabled:true}")
    private boolean seasonRecommendationEnabled;

    public AnimeSeasonSource(AniListClient aniListClient,
                             AnimeSubscriptionStore subscriptionStore,
                             NotificationEventPublisher publisher,
                             LLMClient llmClient) {
        this.aniListClient = aniListClient;
        this.subscriptionStore = subscriptionStore;
        this.publisher = publisher;
        this.llmClient = llmClient;
    }

    @Override
    public String getName() { return "ANIME_SEASON"; }


    @Override
    public void check() {
        try {
            log.info("===== AnimeSeasonSource 季度推荐 =====");

            // 获取当季新番
            List<Anime> seasonAnime = aniListClient.getCurrentSeasonAnime(1);
            if (seasonAnime.isEmpty()) {
                log.warn("未获取到当季新番数据");
                return;
            }

            // 阶段1：规则预筛
            List<Anime> prefiltered = seasonAnime.stream()
                .filter(a -> a.getAverageScore() >= 70 || a.getPopularity() > 50000)
                .limit(20)
                .toList();

            // 排除已订阅番剧
            List<Anime> subscriptions = subscriptionStore.listAll();
            List<Integer> subscribedIds = subscriptions.stream()
                .map(Anime::getAnilistId).toList();
            List<Anime> candidates = prefiltered.stream()
                .filter(a -> !subscribedIds.contains(a.getAnilistId()))
                .toList();

            if (candidates.isEmpty()) {
                log.info("无新番可推荐");
                return;
            }

            // 阶段2：LLM 个性化推荐（中文译名 + 结合订阅偏好 + 逐条换行）
            List<Anime> top5 = candidates.stream().limit(5).toList();
            String content = generateChineseRecommendation(subscriptions, top5);

            // 获取当季信息
            String season = AniListClient.getCurrentSeason();
            int year = LocalDate.now(ZoneId.of("Asia/Tokyo")).getYear();

            // 构建推送内容
            String title = "📺 " + year + "年" + season + "季新番推荐";

            // 通过统一事件总线推送（批次 3 落地）
            publisher.publish("ANIME_SEASON", title, content, 4);

            log.info("季度推荐完成 | candidates={} | recommended={}",
                candidates.size(), top5.size());
        } catch (Exception e) {
            log.error("AnimeSeasonSource 异常", e);
        }
    }

    /**
     * 生成中文推荐内容。
     *
     * <p>让 LLM 结合用户订阅偏好，将候选番剧转为中文译名、逐条换行的推荐列表；
     * LLM 调用失败或返回空时，回退为罗马音逐条列表（避免推送为空）。
     */
    private String generateChineseRecommendation(List<Anime> subscriptions, List<Anime> top5) {
        try {
            String result = llmClient.chatWithSystemPrompt(
                    "你是番剧推荐助手。", buildRecommendationPrompt(subscriptions, top5), 900);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
            log.warn("LLM 推荐生成返回空，使用原片名兜底");
        } catch (Exception e) {
            log.warn("LLM 推荐生成失败，使用原片名兜底", e);
        }
        return fallbackList(top5);
    }

    private String buildRecommendationPrompt(List<Anime> subscriptions, List<Anime> top5) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于用户已订阅番剧和候选当季新番，推荐 5 部最合适的番剧。\n\n");

        if (subscriptions.isEmpty()) {
            sb.append("用户已订阅：暂无\n");
        } else {
            sb.append("用户已订阅：\n");
            for (Anime s : subscriptions) {
                sb.append("- ").append(s.getTitle())
                        .append(s.getGenres() != null && !s.getGenres().isEmpty()
                                ? "（" + String.join("/", s.getGenres()) + "）" : "")
                        .append("\n");
            }
        }

        sb.append("\n候选番剧：\n");
        for (int i = 0; i < top5.size(); i++) {
            Anime a = top5.get(i);
            sb.append(i + 1).append(". 名称:").append(a.getTitle())
                    .append(" 日文名:").append(a.getTitleJa() != null ? a.getTitleJa() : "")
                    .append(" 类型:").append(a.getGenres() != null ? String.join("/", a.getGenres()) : "")
                    .append(" 评分:").append(a.getAverageScore())
                    .append("\n");
        }

        sb.append("\n要求：\n")
                .append("1. 挑选 5 部，逐条一行，用「• 」开头，每行格式：• 《中文译名》原片名（类型）\n")
                .append("2. 番剧名必须用中文译名（官方中文译名优先），无法确认时保留原片名并在括号内给出中文译名，不要只显示罗马音或日文\n")
                .append("3. 结合用户订阅偏好给出推荐顺序\n")
                .append("4. 只输出列表本身，不要任何解释或前言");
        return sb.toString();
    }

    private String fallbackList(List<Anime> top5) {
        return top5.stream()
            .map(a -> "• " + a.getTitle() + (a.getGenres() != null ? " (" + String.join("/", a.getGenres()) + ")" : ""))
            .collect(Collectors.joining("\n"));
    }
}
