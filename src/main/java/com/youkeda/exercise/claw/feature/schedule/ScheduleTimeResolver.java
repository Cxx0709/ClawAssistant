package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.domain.schedule.SchoolEntity;
import com.youkeda.exercise.claw.identity.UserProfileRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 课程时间解析服务
 *
 * <p>根据学校作息配置将课程节次号解析为具体时间。
 * 解析链路：
 * <pre>
 *   userId
 *     → UserProfileRepository.getSchoolId(userId)    // 获取用户绑定的学校
 *       → SchoolScheduleConfigRepository.findBySchoolId(schoolId)  // 获取学校作息
 *         → 返回时间段
 * </pre>
 *
 * <p>如果用户没有绑定学校（schoolId == null），回退使用默认学校的作息配置。
 * 结果会被缓存以提升频繁查询的性能。
 */
@Service
public class ScheduleTimeResolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTimeResolver.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final SchoolScheduleConfigRepository repository;
    private final UserProfileRepository userProfiles;

    /** 学校作息缓存：schoolId -> {periodNumber -> [startTime, endTime]} */
    private final ConcurrentHashMap<Long, Map<Integer, String[]>> cache = new ConcurrentHashMap<>();

    /** 默认学校 ID 缓存（首次解析时加载） */
    private volatile Long defaultSchoolId;

    public ScheduleTimeResolver(SchoolScheduleConfigRepository repository,
                                UserProfileRepository userProfiles) {
        this.repository = repository;
        this.userProfiles = userProfiles;
    }

    @PostConstruct
    public void init() {
        log.info("课程时间解析服务已启动（学校级作息配置）");
    }

    // ==================== 核心解析方法 ====================

    public boolean hasBoundSchool(String userId) {
        return userProfiles.getSchoolId(userId) != null;
    }

    /**
     * 获取某节课的开始时间
     *
     * @param userId       用户标识
     * @param periodNumber 节次号（从1开始）
     * @return 开始时间，如果节次号无效返回 null
     */
    public LocalTime getStartTime(String userId, int periodNumber) {
        String[] times = resolvePeriod(userId, periodNumber);
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
        String[] times = resolvePeriod(userId, periodNumber);
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
     * @param userId       用户标识
     * @param startPeriod  开始节次
     * @param endPeriod    结束节次
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
     * @param userId       用户标识
     * @param startPeriod  开始节次
     * @param endPeriod    结束节次
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
     * @param userId       用户标识
     * @param startPeriod  开始节次
     * @param endPeriod    结束节次
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

    // ==================== 内部解析与缓存 ====================

    /**
     * 解析节次时间
     *
     * <p>解析链路：userId → schoolId → school schedule config → 返回时间。
     * 如果用户无学校绑定，使用默认学校作息。
     */
    private String[] resolvePeriod(String userId, int periodNumber) {
        if (periodNumber < 1) return null;

        // 1. 获取用户绑定的学校 ID
        Long schoolId = null;
        if (userId != null && !userId.isBlank()) {
            schoolId = userProfiles.getSchoolId(userId);
        }

        // 2. 如果无学校绑定，使用默认学校
        if (schoolId == null) {
            schoolId = getDefaultSchoolId();
        }

        if (schoolId == null) {
            return null; // 没有可用学校
        }

        // 3. 查缓存
        Map<Integer, String[]> schedule = cache.get(schoolId);
        if (schedule != null) {
            String[] times = schedule.get(periodNumber);
            if (times != null) return times;
        }

        // 4. 从数据库加载学校作息
        schedule = loadScheduleToCache(schoolId);
        if (schedule != null) {
            return schedule.get(periodNumber);
        }

        return null;
    }

    /**
     * 从数据库加载学校作息配置到缓存
     */
    private Map<Integer, String[]> loadScheduleToCache(Long schoolId) {
        List<SchoolScheduleConfig> configs = repository.findBySchoolId(schoolId);
        if (configs.isEmpty()) {
            return null;
        }

        Map<Integer, String[]> schedule = new HashMap<>();
        for (SchoolScheduleConfig cfg : configs) {
            schedule.put(cfg.getPeriodNumber(), new String[]{cfg.getStartTime(), cfg.getEndTime()});
        }
        cache.put(schoolId, schedule);
        return schedule;
    }

    /**
     * 获取默认学校 ID（首次调用时加载）
     */
    private Long getDefaultSchoolId() {
        if (defaultSchoolId == null) {
            synchronized (this) {
                if (defaultSchoolId == null) {
                    SchoolEntity defaultSchool = repository.findDefaultSchool();
                    if (defaultSchool != null) {
                        defaultSchoolId = defaultSchool.getId();
                        log.info("默认学校已加载 | id={} | name={}",
                                defaultSchoolId, defaultSchool.getSchoolName());
                    }
                }
            }
        }
        return defaultSchoolId;
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除指定用户的学校作息缓存
     *
     * @param userId 用户标识
     */
    public void clearCache(String userId) {
        if (userId == null || userId.isBlank()) {
            cache.clear();
            return;
        }
        Long schoolId = userProfiles.getSchoolId(userId);
        if (schoolId != null) {
            cache.remove(schoolId);
        } else {
            // 用户无学校绑定 → 清除默认学校缓存
            cache.remove(defaultSchoolId);
        }
    }

    /**
     * 清除指定学校的缓存
     *
     * @param schoolId 学校 ID
     */
    public void clearSchoolCache(Long schoolId) {
        if (schoolId != null) {
            cache.remove(schoolId);
        }
    }

    /**
     * 清除全部缓存
     */
    public void clearAllCache() {
        cache.clear();
        defaultSchoolId = null; // 下次重新加载
    }
}
