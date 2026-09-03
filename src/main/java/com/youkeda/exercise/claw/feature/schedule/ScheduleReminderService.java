package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.notification.NotificationSink;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 课前提醒服务
 *
 * <p>无独立调度线程，依赖外部（{@link com.youkeda.exercise.claw.feature.task.scheduler.TaskSchedulerService}）
 * 周期性调用 {@link #checkReminders()} 来扫描课程并发送提醒。
 *
 * <p>扫描逻辑：
 * <ul>
 *   <li>每 60 秒扫描一次所有用户的课程（由 {@code TaskSchedulerService} 触发）</li>
 *   <li>课程开始前 30 分钟（±30 秒容差）发送站内提醒</li>
 *   <li>自动判断当前教学周和单双周</li>
 * </ul>
 *
 * <p>提醒去重：使用内存缓存 {@link #notifiedCache} 避免同一天同一门课重复发送。
 * 缓存最多保留 10000 条，超限时全量清理。
 *
 * <p>课程开始时间通过 {@link ScheduleTimeResolver} 根据用户绑定的学校作息配置动态计算，
 * 不再使用硬编码的固定节次时间表。
 */
@Component
public class ScheduleReminderService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReminderService.class);

    /** 提醒提前量：30 分钟（可通过 schedule.reminder.advance-minutes 配置） */
    @Value("${schedule.reminder.advance-minutes:30}")
    private int reminderAdvanceMinutes;

    /** 提醒窗口容差（秒）：允许提前或滞后触发（可通过 schedule.reminder.tolerance-seconds 配置） */
    @Value("${schedule.reminder.tolerance-seconds:30}")
    private int reminderToleranceSeconds;

    private final CourseRepository courseRepository;
    private final SemesterConfig semesterConfig;
    private final NotificationSink notificationSink;
    private final SemesterService semesterService;
    private final ScheduleTimeResolver timeResolver;

    /** 已发送提醒的课程 ID 缓存（避免重复发送），key = userId:courseId:yyyyMMdd */
    private final ConcurrentHashMap<String, Boolean> notifiedCache = new ConcurrentHashMap<>();

    public ScheduleReminderService(CourseRepository courseRepository,
                                   SemesterConfig semesterConfig,
                                   NotificationSink notificationSink,
                                   SemesterService semesterService,
                                   ScheduleTimeResolver timeResolver) {
        this.courseRepository = courseRepository;
        this.semesterConfig = semesterConfig;
        this.notificationSink = notificationSink;
        this.semesterService = semesterService;
        this.timeResolver = timeResolver;
    }

    @PostConstruct
    public void init() {
        log.info("课前提醒服务已启动 | advance={}min | tolerance={}s",
                reminderAdvanceMinutes, reminderToleranceSeconds);
    }

    /**
     * 扫描全部课程，检查是否需要发送提醒。
     *
     * <p>由 {@code TaskSchedulerService} 定期调用（约每 60 秒一次）。
     * 多次调用安全：内部使用去重缓存防止重复发送。
     */
    public void checkReminders() {
        try {
            List<CourseEntity> allCourseEntitys = courseRepository.findAll();
            if (allCourseEntitys.isEmpty()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            int today = LocalDate.now().getDayOfWeek().getValue();

            // 按用户分组，每个用户独立计算当前教学周
            Map<String, List<CourseEntity>> coursesByUser = allCourseEntitys.stream()
                    .collect(Collectors.groupingBy(CourseEntity::getUserId));

            int sentCount = 0;
            for (Map.Entry<String, List<CourseEntity>> entry : coursesByUser.entrySet()) {
                String userId = entry.getKey();
                int currentWeek = resolveCurrentWeek(userId);
                if (currentWeek <= 0) {
                    continue; // 该用户学期未开始
                }

                for (CourseEntity course : entry.getValue()) {
                    try {
                        if (checkCourseEntity(course, currentWeek, today, now)) {
                            sentCount++;
                        }
                    } catch (Exception e) {
                        log.warn("检查课程提醒异常 | userId={} | courseId={} | error={}",
                                userId, course.getId(), e.getMessage());
                    }
                }
            }

            if (sentCount > 0) {
                log.info("课前提醒扫描完成 | sent={} | totalCourseEntitys={}", sentCount, allCourseEntitys.size());
            }

            // 清理缓存（避免内存泄漏）
            cleanCacheIfNeeded();

        } catch (Exception e) {
            log.error("课前提醒扫描异常", e);
        }
    }

    /**
     * 解析用户当前教学周（优先 SemesterService，回退 SemesterConfig）
     */
    private int resolveCurrentWeek(String userId) {
        int week = semesterService.getCurrentWeek(userId);
        if (week > 0) {
            return week;
        }
        return semesterConfig.getCurrentWeek();
    }

    /**
     * 检查单条课程是否需要发送提醒
     *
     * @return true 表示已发送提醒
     */
    private boolean checkCourseEntity(CourseEntity course, int currentWeek, int today, LocalDateTime now) {
        // 1. 不是今天的课 → 跳过
        if (course.getDayOfWeek() != today) return false;

        // 2. 本周不上课 → 跳过
        if (!course.isActiveInWeek(currentWeek)) return false;

        // 3. 计算课程开始时间
        LocalDateTime courseStartTime = getCourseEntityStartTime(course);
        if (courseStartTime == null) return false;

        // 4. 计算提醒时间窗口
        LocalDateTime reminderTarget = courseStartTime.minusMinutes(reminderAdvanceMinutes);
        long diffSeconds = Duration.between(now, reminderTarget).abs().getSeconds();

        if (diffSeconds > reminderToleranceSeconds) return false;

        // 5. 检查是否已发送过（同一天同一门课不重复）
        String cacheKey = course.getUserId() + ":" + course.getId() + ":"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        if (notifiedCache.putIfAbsent(cacheKey, Boolean.TRUE) != null) return false;

        // 6. 发送提醒
        sendReminder(course, courseStartTime, currentWeek);
        return true;
    }

    /**
     * 发送课前站内提醒
     */
    private void sendReminder(CourseEntity course, LocalDateTime courseStartTime, int currentWeek) {
        String timeStr = courseStartTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        String message = buildReminderMessage(course, timeStr, currentWeek);

        try {
            notificationSink.publish(course.getUserId(), "COURSE_REMINDER", "课前提醒",
                    message, 5, null);
            log.info("课前提醒已发送 | userId={} | course={} | time={}",
                    course.getUserId(), course.getCourseName(), timeStr);
        } catch (Exception e) {
            log.error("课前提醒发送失败 | userId={} | course={}",
                    course.getUserId(), course.getCourseName(), e);
        }
    }

    /**
     * 构建提醒消息文本
     */
    private String buildReminderMessage(CourseEntity course, String timeStr, int currentWeek) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏰ **课前提醒**\n");
        sb.append("────────────────\n\n");
        sb.append("📖 ").append(course.getCourseName()).append("\n");
        sb.append("🕐 ").append(timeStr).append("  (").append(course.getPeriodDisplay()).append("节)\n");
        if (!course.getClassroom().isBlank()) {
            sb.append("🏫 ").append(course.getClassroom()).append("\n");
        }
        if (!course.getTeacher().isBlank()) {
            sb.append("👨‍🏫 ").append(course.getTeacher()).append("\n");
        }
        sb.append("\n📆 第").append(currentWeek).append("周");
        if (!CourseEntity.WEEK_ALL.equals(course.getWeekType())) {
            sb.append(" (").append(
                CourseEntity.WEEK_ODD.equals(course.getWeekType()) ? "单周" : "双周"
            ).append(")");
        }
        sb.append("\n────────────────\n");
        sb.append("💡 别迟到哦！");
        return sb.toString();
    }

    /**
     * 根据课程节次计算今日开始时间（通过用户绑定的学校作息配置）
     */
    private LocalDateTime getCourseEntityStartTime(CourseEntity course) {
        var startTime = timeResolver.getStartTime(course.getUserId(), course.getStartPeriod());
        if (startTime == null) return null;
        LocalDateTime now = LocalDateTime.now();
        return now.withHour(startTime.getHour())
                  .withMinute(startTime.getMinute())
                  .withSecond(0).withNano(0);
    }

    /**
     * 清理去重缓存，避免内存泄漏
     */
    private void cleanCacheIfNeeded() {
        if (notifiedCache.size() > 10000) {
            notifiedCache.clear();
            log.info("课前提醒缓存已清理");
        }
    }
}
