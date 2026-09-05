package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.notification.NotificationSink;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 每日课表提醒服务
 *
 * <p>无独立调度线程，依赖外部（{@link com.youkeda.exercise.claw.feature.task.scheduler.TaskSchedulerService}）
 * 周期性调用 {@link #checkReminders()} 来扫描并发送提醒。
 *
 * <p>提醒逻辑：
 * <ul>
 *   <li>每天在固定时间（{@code schedule.daily-reminder.time}，默认 07:30）向有课用户推送当日课表汇总</li>
 *   <li>错过窗口（超过 {@code schedule.daily-reminder.grace-minutes}，默认 120 分钟）则当天不再补发</li>
 *   <li>自动判断当前教学周和单双周，学期未配置或当天无课的用户不推送</li>
 * </ul>
 *
 * <p>历史说明：曾按各学校作息做「课前 30 分钟」提醒，因学校绑定功能从未开放、
 * 所有用户实际共用默认作息，已改为每日定时推送当日课表。
 *
 * <p>去重：内存缓存 key = userId:date，每用户每天最多一条。
 */
@Component
public class ScheduleReminderService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReminderService.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 每日提醒时间（HH:mm，可通过 schedule.daily-reminder.time 配置） */
    @Value("${schedule.daily-reminder.time:07:30}")
    private String reminderTime = "07:30";

    /** 错过提醒时间后的补发宽限期（分钟，可通过 schedule.daily-reminder.grace-minutes 配置） */
    @Value("${schedule.daily-reminder.grace-minutes:120}")
    private int graceMinutes = 120;

    private final CourseRepository courseRepository;
    private final SemesterConfig semesterConfig;
    private final NotificationSink notificationSink;
    private final SemesterService semesterService;
    private final ScheduleTimeResolver timeResolver;
    private final CourseService courseService;

    /** 已发送缓存，key = userId:date（yyyyMMdd），每用户每天最多一条。 */
    private final ConcurrentHashMap<String, Boolean> notifiedCache = new ConcurrentHashMap<>();

    public ScheduleReminderService(CourseRepository courseRepository,
                                   SemesterConfig semesterConfig,
                                   NotificationSink notificationSink,
                                   SemesterService semesterService,
                                   ScheduleTimeResolver timeResolver,
                                   CourseService courseService) {
        this.courseRepository = courseRepository;
        this.semesterConfig = semesterConfig;
        this.notificationSink = notificationSink;
        this.semesterService = semesterService;
        this.timeResolver = timeResolver;
        this.courseService = courseService;
    }

    @PostConstruct
    public void init() {
        log.info("每日课表提醒服务已启动 | time={} | grace={}min", reminderTime, graceMinutes);
    }

    /**
     * 扫描全部用户，检查是否到达每日提醒时间。
     *
     * <p>由 {@code TaskSchedulerService} 定期调用（约每 60 秒一次）。
     * 多次调用安全：内部使用去重缓存防止重复发送。
     */
    public void checkReminders() {
        checkReminders(LocalDateTime.now());
    }

    void checkReminders(LocalDateTime now) {
        try {
            LocalTime target = parseReminderTime();
            LocalDateTime targetAt = now.toLocalDate().atTime(target);
            if (now.isBefore(targetAt) || now.isAfter(targetAt.plusMinutes(graceMinutes))) {
                return; // 未到提醒时间或已错过窗口
            }

            List<CourseEntity> allCourses = courseRepository.findAll();
            if (allCourses.isEmpty()) {
                return;
            }

            String dateKey = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
            int sentCount = 0;

            // 按用户分组，每个用户独立判断当天是否有课
            for (String userId : allCourses.stream()
                    .map(CourseEntity::getUserId).distinct().collect(Collectors.toList())) {
                if (notifiedCache.putIfAbsent(userId + ":" + dateKey, Boolean.TRUE) != null) {
                    continue; // 今天已提醒
                }
                try {
                    if (sendDailyDigest(userId, now.toLocalDate())) {
                        sentCount++;
                    } else {
                        notifiedCache.remove(userId + ":" + dateKey); // 允许宽限期内重试
                    }
                } catch (Exception e) {
                    notifiedCache.remove(userId + ":" + dateKey);
                    log.warn("每日课表提醒异常 | userId={} | error={}", userId, e.getMessage());
                }
            }

            if (sentCount > 0) {
                log.info("每日课表提醒完成 | sent={} | date={}", sentCount, now.toLocalDate());
            }
            cleanCacheIfNeeded();

        } catch (Exception e) {
            log.error("每日课表提醒扫描异常", e);
        }
    }

    /**
     * 向单个用户发送当日课表汇总
     *
     * @return true 表示已发送
     */
    private boolean sendDailyDigest(String userId, LocalDate date) {
        CourseService.DateCourses schedule = courseService.getCoursesOnDate(userId, date);
        if (!schedule.calendarConfigured() || schedule.week() <= 0 || schedule.courses().isEmpty()) {
            return false; // 学期未开始或今天没课，不推送
        }

        String message = buildDigestMessage(schedule.courses(), schedule.week(), date);
        try {
            notificationSink.publish(userId, "COURSE_DAILY", "今日课表", message, 5, null);
            log.info("每日课表提醒已发送 | userId={} | courseCount={}", userId, schedule.courses().size());
            return true;
        } catch (Exception e) {
            log.error("每日课表提醒发送失败 | userId={}", userId, e);
            return false;
        }
    }

    /**
     * 构建当日课表汇总消息
     */
    private String buildDigestMessage(List<CourseEntity> courses, int currentWeek, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("📅 **今日课表**\n");
        sb.append(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE")))
          .append(" · 第").append(currentWeek).append("周\n");
        sb.append("────────────────\n\n");

        List<CourseEntity> sorted = courses.stream()
                .sorted(java.util.Comparator.comparingInt(CourseEntity::getStartPeriod))
                .toList();
        for (CourseEntity course : sorted) {
            String timeRange = timeResolver.formatTimeRange(course.getUserId(),
                    course.getStartPeriod(), course.getEndPeriod());
            sb.append("📖 ").append(course.getCourseName());
            if (timeRange != null && !timeRange.isBlank()) {
                sb.append("  ").append(timeRange).append(" (").append(course.getPeriodDisplay()).append("节)");
            }
            sb.append("\n");
            if (course.getClassroom() != null && !course.getClassroom().isBlank()) {
                sb.append("🏫 ").append(course.getClassroom()).append("\n");
            }
            if (course.getTeacher() != null && !course.getTeacher().isBlank()) {
                sb.append("👨‍🏫 ").append(course.getTeacher()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("────────────────\n");
        sb.append("共 ").append(sorted.size()).append(" 门课，加油！💡");
        return sb.toString();
    }

    public Map<String, Object> getStatus(String userId) {
        List<CourseEntity> courses = courseRepository.findByUserId(userId);
        boolean calendarConfigured = semesterService.hasSemester(userId) || semesterConfig.getSemesterStart() != null;
        String status = courses.isEmpty() ? "no_courses"
                : !calendarConfigured ? "missing_semester" : "ready";
        String message = switch (status) {
            case "no_courses" -> "尚无课程，请先导入课表";
            case "missing_semester" -> "尚未设置学期起始日期，无法判断实际教学周";
            default -> "系统每天 " + reminderTime + " 推送当日课表汇总，当天无课不推送";
        };
        return Map.of("status", status, "calendar_configured", calendarConfigured,
                "reminder_time", reminderTime, "course_count", courses.size(),
                "message", message);
    }

    /**
     * 清理去重缓存，避免内存泄漏
     */
    private void cleanCacheIfNeeded() {
        if (notifiedCache.size() > 10000) {
            notifiedCache.clear();
            log.info("每日课表提醒缓存已清理");
        }
    }

    private LocalTime parseReminderTime() {
        try {
            return LocalTime.parse(reminderTime, TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("提醒时间配置非法（{}），回退默认 07:30", reminderTime);
            return LocalTime.of(7, 30);
        }
    }
}
