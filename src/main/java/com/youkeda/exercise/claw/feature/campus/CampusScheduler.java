package com.youkeda.exercise.claw.feature.campus;

import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.feature.campus.store.CampusConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CampusScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampusScheduler.class);

    private final CampusNotifier notifier;
    private final CampusConfigStore configStore;

    public CampusScheduler(CampusNotifier notifier, CampusConfigStore configStore) {
        this.notifier = notifier;
        this.configStore = configStore;
    }

    /** 每天 08:00 执行各类通知检查 */
    @Scheduled(cron = "0 0 8 * * *")
    public void dailyCheck() {
        CampusConfig config = configStore.get();
        if (config == null || !config.isEnabled()) {
            log.debug("校园提醒未配置或已关闭，跳过");
            return;
        }
        log.info("===== CampusScheduler 启动 | school={} =====", config.getSchool());
        notifier.notifyAll(config);
    }
}
