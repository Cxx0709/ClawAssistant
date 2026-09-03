package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 学期配置
 *
 * <p>根据学期起始日期计算当前教学周次和单双周。
 * 配置项：{@code schedule.semester-start}，格式 yyyy-MM-dd，默认当天。
 */
@Component
@ConfigurationProperties(prefix = "schedule")
public class SemesterConfig {

    private static final Logger log = LoggerFactory.getLogger(SemesterConfig.class);

    /** 学期第一周的周一日期 */
    private LocalDate semesterStart;

    public LocalDate getSemesterStart() {
        return semesterStart;
    }

    public void setSemesterStart(LocalDate semesterStart) {
        this.semesterStart = semesterStart;
        log.info("学期起始日期已设置 | semesterStart={}", semesterStart);
    }

    /**
     * 计算当前教学周（从 semesterStart 所在的周一开始算第 1 周）
     *
     * @return 当前教学周，学期未开始或已结束返回 -1
     */
    public int getCurrentWeek() {
        if (semesterStart == null) {
            log.debug("学期起始日期未配置，返回默认周次 1");
            return 1;
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore(semesterStart)) {
            return -1; // 学期未开始
        }
        // 计算与学期第一周周一的间隔天数
        long daysBetween = ChronoUnit.DAYS.between(semesterStart, today);
        int week = (int) (daysBetween / 7) + 1;
        log.debug("当前教学周 | today={} | semesterStart={} | week={}", today, semesterStart, week);
        return week;
    }

    /**
     * 判断当前周是否为单周
     */
    public boolean isOddWeek() {
        int week = getCurrentWeek();
        return week > 0 && week % 2 == 1;
    }

    /**
     * 判断当前周是否为双周
     */
    public boolean isEvenWeek() {
        int week = getCurrentWeek();
        return week > 0 && week % 2 == 0;
    }

    /**
     * 获取当前星期几（1=周一 ~ 7=周日）
     */
    public int getCurrentDayOfWeek() {
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        return dow.getValue(); // DayOfWeek: 1=Mon ~ 7=Sun
    }
}