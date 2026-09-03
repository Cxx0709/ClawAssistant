package com.youkeda.exercise.claw.feature.campus;

import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.notification.NotificationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CampusNotifier {

    private static final Logger log = LoggerFactory.getLogger(CampusNotifier.class);

    private final List<NotificationSource> sources;

    public CampusNotifier(List<NotificationSource> sources) {
        this.sources = sources;
    }

    /**
     * 遍历所有 Source 执行检查
     * 每个 Source 内部自行判断配置是否支持
     */
    public void notifyAll(CampusConfig config) {
        for (NotificationSource source : sources) {
            try {
                if (!config.isSourceEnabled(source.getName())) continue;
                log.info("通知检查启动 | source={}", source.getName());
                source.check();
            } catch (Exception e) {
                log.error("通知检查异常 | source={}", source.getName(), e);
                // 不中断其他 Source
            }
        }
    }
}
