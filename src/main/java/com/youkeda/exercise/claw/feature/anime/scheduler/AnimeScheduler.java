package com.youkeda.exercise.claw.feature.anime.scheduler;

import com.youkeda.exercise.claw.feature.anime.notification.AnimeSource;
import com.youkeda.exercise.claw.feature.anime.notification.AnimeSeasonSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@ConditionalOnProperty(name = "anime.enabled", havingValue = "true")
public class AnimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnimeScheduler.class);

    private final AnimeSource animeSource;
    private final AnimeSeasonSource animeSeasonSource;

    public AnimeScheduler(AnimeSource animeSource, AnimeSeasonSource animeSeasonSource) {
        this.animeSource = animeSource;
        this.animeSeasonSource = animeSeasonSource;
    }

    /** 每天 08:00 检查播出更新 */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyCheck() {
        log.info("===== AnimeScheduler 每日检查 =====");

        // 1. 检查播出更新
        animeSource.check();

        // 2. 每季第一天触发新番推荐
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        if (today.getDayOfMonth() == 1
            && (today.getMonthValue() == 1 || today.getMonthValue() == 4
                || today.getMonthValue() == 7 || today.getMonthValue() == 10)) {
            animeSeasonSource.check();
        }
    }
}
