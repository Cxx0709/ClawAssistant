package com.youkeda.exercise.claw.feature.campus.notification;
import com.youkeda.exercise.claw.notification.NotificationSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 课程提醒占位 Source。
 * 等待 vae-tools 的 schedule 模块融合后启用。
 */
@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CourseReminderSource implements NotificationSource {

    private static final Logger log = LoggerFactory.getLogger(CourseReminderSource.class);

    @Override
    public String getName() { return "COURSE"; }

    @Override
    public void check() {
        // 空实现，融合 vae-tools 的 ScheduleReminderService 后填充
        log.debug("CourseReminderSource 未启用");
    }
}
