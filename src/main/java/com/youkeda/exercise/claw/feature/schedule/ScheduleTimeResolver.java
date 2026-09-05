package com.youkeda.exercise.claw.feature.schedule;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 课程时间解析服务
 *
 * <p>将课程节次号解析为具体时间，使用内置默认作息表（08:00 上课、45 分钟制、12 节）。
 *
 * <p>历史说明：曾支持按学校绑定动态作息（schools / school_schedule_config 表），
 * 因用户无法绑定学校、所有人实际都落到默认作息，整条链路已删除。
 * 如未来需要按学校区分作息，再引入配置化方案。
 */
@Service
public class ScheduleTimeResolver {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 内置默认作息表：下标 = 节次号，[0] 占位。 */
    private static final String[][] DEFAULT_TIMETABLE = {
            {},
            {"08:00", "08:45"}, {"08:55", "09:40"}, {"10:10", "10:55"}, {"11:05", "11:50"},
            {"13:30", "14:15"}, {"14:25", "15:10"}, {"15:40", "16:25"}, {"16:35", "17:20"},
            {"17:30", "18:15"}, {"19:00", "19:45"}, {"19:55", "20:40"}, {"20:50", "21:35"}
    };

    // ==================== 核心解析方法 ====================

    /**
     * 获取某节课的开始时间
     *
     * @param userId       用户标识（保留参数以兼容调用方，当前所有用户共用默认作息）
     * @param periodNumber 节次号（从1开始）
     * @return 开始时间，如果节次号无效返回 null
     */
    public LocalTime getStartTime(String userId, int periodNumber) {
        String[] times = resolvePeriod(periodNumber);
        if (times == null) return null;
        return LocalTime.parse(times[0], TIME_FORMATTER);
    }

    /**
     * 获取某节课的结束时间
     *
     * @param userId       用户标识
     * @param periodNumber 节次号（从1开始）
     * @return 结束时间，如果节次号无效返回 null
     */
    public LocalTime getEndTime(String userId, int periodNumber) {
        String[] times = resolvePeriod(periodNumber);
        if (times == null) return null;
        return LocalTime.parse(times[1], TIME_FORMATTER);
    }

    /**
     * 获取某节课的时长（分钟）
     *
     * @param userId       用户标识
     * @param periodNumber 节次号（从1开始）
     * @return 时长（分钟），如果节次号无效返回 0
     */
    public long getDurationMinutes(String userId, int periodNumber) {
        LocalTime start = getStartTime(userId, periodNumber);
        LocalTime end = getEndTime(userId, periodNumber);
        if (start == null || end == null) return 0;
        return Duration.between(start, end).toMinutes();
    }

    /**
     * 获取连续课程的完整时间范围（从第一节课开始到最后一节课结束）
     *
     * @param userId      用户标识
     * @param startPeriod 开始节次
     * @param endPeriod   结束节次
     * @return 包含开始和结束时间的数组 [startTime, endTime]，如果节次无效返回 null
     */
    public LocalTime[] getCourseTimeRange(String userId, int startPeriod, int endPeriod) {
        LocalTime start = getStartTime(userId, startPeriod);
        LocalTime end = getEndTime(userId, endPeriod);
        if (start == null || end == null) return null;
        return new LocalTime[]{start, end};
    }

    /**
     * 获取连续课程的时间范围文本，如 "08:00-09:40"
     *
     * @param userId      用户标识
     * @param startPeriod 开始节次
     * @param endPeriod   结束节次
     * @return 时间范围文本，如果无法解析返回空字符串
     */
    public String formatTimeRange(String userId, int startPeriod, int endPeriod) {
        LocalTime[] range = getCourseTimeRange(userId, startPeriod, endPeriod);
        if (range == null) return "";
        return range[0].format(TIME_FORMATTER) + "-" + range[1].format(TIME_FORMATTER);
    }

    /**
     * 获取包括节次和时间的完整显示，如 "第1-2节 (08:00-09:40)"
     *
     * @param userId      用户标识
     * @param startPeriod 开始节次
     * @param endPeriod   结束节次
     * @return 格式化文本
     */
    public String formatPeriodWithTime(String userId, int startPeriod, int endPeriod) {
        String periodLabel = startPeriod == endPeriod
                ? "第" + startPeriod + "节"
                : "第" + startPeriod + "-" + endPeriod + "节";
        String timeRange = formatTimeRange(userId, startPeriod, endPeriod);
        if (timeRange.isEmpty()) return periodLabel;
        return periodLabel + " (" + timeRange + ")";
    }

    // ==================== 内部解析 ====================

    private String[] resolvePeriod(int periodNumber) {
        if (periodNumber < 1 || periodNumber >= DEFAULT_TIMETABLE.length) return null;
        String[] times = DEFAULT_TIMETABLE[periodNumber];
        if (times == null || times.length != 2) return null;
        return times;
    }
}
