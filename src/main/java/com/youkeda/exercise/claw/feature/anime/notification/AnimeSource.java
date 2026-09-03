package com.youkeda.exercise.claw.feature.anime.notification;
import com.youkeda.exercise.claw.notification.NotificationSource;

import com.youkeda.exercise.claw.feature.anime.client.AniListClient;
import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.domain.anime.AnimeEpisode;
import com.youkeda.exercise.claw.feature.anime.store.AnimeScheduleStore;
import com.youkeda.exercise.claw.feature.anime.store.AnimeSubscriptionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class AnimeSource implements NotificationSource {

    private static final Logger log = LoggerFactory.getLogger(AnimeSource.class);

    private final AniListClient aniListClient;
    private final AnimeSubscriptionStore subscriptionStore;
    private final AnimeScheduleStore scheduleStore;

    public AnimeSource(AniListClient aniListClient,
                       AnimeSubscriptionStore subscriptionStore,
                       AnimeScheduleStore scheduleStore) {
        this.aniListClient = aniListClient;
        this.subscriptionStore = subscriptionStore;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public String getName() { return "ANIME"; }

    @Override
    public void check() {
        try {
            List<Anime> airingAnime = subscriptionStore.getCurrentlyAiring();
            if (airingAnime.isEmpty()) {
                log.debug("无正在播出的番剧，跳过检查");
                return;
            }

            long now = System.currentTimeMillis() / 1000;
            long future24h = now + 24 * 3600;

            for (Anime anime : airingAnime) {
                try {
                    AnimeEpisode episode = aniListClient.getAiringSchedule(anime.getAnilistId());
                    if (episode == null || episode.getEpisode() <= 0) continue;

                    // 插入播出记录（已存在则忽略）
                    boolean isNew = scheduleStore.insertOrIgnoreEpisode(
                        anime.getAnilistId(), episode.getEpisode(), episode.getAiringAt());
                    if (!isNew) continue;

                    // 如果未来 24h 内播出，生成提醒任务
                    if (episode.getAiringAt() <= future24h) {
                        long remindTime = episode.getAiringAt() - 15 * 60; // 提前 15 分钟
                        scheduleStore.createReminderTask(
                            anime.getAnilistId(), episode.getEpisode(), remindTime, episode.getAiringAt());
                        log.info("已生成提醒任务 | title={} | episode={} | remindTime={}",
                            anime.getTitle(), episode.getEpisode(), remindTime);
                    }
                } catch (Exception e) {
                    log.warn("检查番剧失败 | id={} | title={}", anime.getAnilistId(), anime.getTitle(), e);
                }
            }
            log.info("AnimeSource 检查完成 | airingCount={}", airingAnime.size());
        } catch (Exception e) {
            log.error("AnimeSource 检查异常", e);
        }
    }
}
