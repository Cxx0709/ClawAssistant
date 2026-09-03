package com.youkeda.exercise.claw.feature.anime.scheduler;

import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.feature.anime.store.AnimeScheduleStore;
import com.youkeda.exercise.claw.feature.anime.store.AnimeSubscriptionStore;
import com.youkeda.exercise.claw.notification.NotificationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final AnimeScheduleStore scheduleStore;
    private final AnimeSubscriptionStore subscriptionStore;
    private final NotificationEventPublisher publisher;

    public ReminderScheduler(AnimeScheduleStore scheduleStore,
                             AnimeSubscriptionStore subscriptionStore,
                             NotificationEventPublisher publisher) {
        this.scheduleStore = scheduleStore;
        this.subscriptionStore = subscriptionStore;
        this.publisher = publisher;
    }

    /** 每分钟执行一次 */
    @Scheduled(cron = "0 * * * * *")
    public void checkReminders() {
        try {
            long now = System.currentTimeMillis() / 1000;
            List<AnimeScheduleStore.ReminderTask> pending = scheduleStore.getPendingReminders(now);
            if (pending.isEmpty()) return;

            for (AnimeScheduleStore.ReminderTask task : pending) {
                try {
                    Anime anime = subscriptionStore.findByAnilistId(task.getAnilistId());
                    if (anime == null) continue;

                    // 格式化播出时间
                    String airTime = Instant.ofEpochSecond(task.getAiringAt())
                        .atZone(ZoneId.of("Asia/Tokyo"))
                        .format(DateTimeFormatter.ofPattern("HH:mm"));

                    String message = "🎬 《" + anime.getTitle() + "》第 "
                        + task.getEpisode() + " 集即将在 " + airTime + " 播出！";

                    // 通过统一事件总线推送（批次 3 落地）
                    publisher.publish("ANIME", "🎬 播出提醒", message, 4);

                    // 标记已发送
                    scheduleStore.markReminderSent(task.getId());
                    scheduleStore.markEpisodeNotified(task.getAnilistId(), task.getEpisode());
                    log.info("播出提醒已发送 | title={} | episode={}",
                        anime.getTitle(), task.getEpisode());
                } catch (Exception e) {
                    log.warn("提醒发送失败 | taskId={}", task.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("ReminderScheduler 异常", e);
        }
    }
}
