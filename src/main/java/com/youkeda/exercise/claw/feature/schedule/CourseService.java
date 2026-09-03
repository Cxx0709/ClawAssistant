package com.youkeda.exercise.claw.feature.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 课表业务服务
 *
 * <p>提供课表导入、今日课程查询、空闲时间查询等核心业务逻辑。
 * 所有持久化操作委托 {@link CourseRepository}，以 {@code userId} 作为隔离键。
 */
@Service
public class CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private static final int MAX_PERIODS = 12;

    private final CourseRepository courseRepository;
    private final CourseParser courseParser;
    private final SemesterConfig semesterConfig;
    private final SemesterService semesterService;

    public CourseService(CourseRepository courseRepository,
                         CourseParser courseParser,
                         SemesterConfig semesterConfig,
                         SemesterService semesterService) {
        this.courseRepository = courseRepository;
        this.courseParser = courseParser;
        this.semesterConfig = semesterConfig;
        this.semesterService = semesterService;
    }

    // ==================== 导入 ====================

    /**
     * 从 LLM 返回的 JSON 文本导入课表（解析 + 持久化一步完成），返回冲突信息
     *
     * @param userId  用户标识
     * @param jsonStr LLM 提取的结构化 JSON
     * @return 导入结果，包含导入数量和冲突列表
     */
    public ImportResult importFromJson(String userId, String jsonStr) {
        List<CourseEntity> courses = courseParser.parseFromJson(jsonStr);
        courses.forEach(c -> c.setUserId(userId));

        // 冲突检测（在持久化之前）
        List<ConflictInfo> conflicts = detectConflicts(userId, courses);

        // 无学期覆盖写入（只清无学期课程，保留学期绑定课程）
        courseRepository.replaceAllNullSemester(userId, courses);

        log.info("课表导入完成 | userId={} | count={} | conflicts={}",
                userId, courses.size(), conflicts.size());

        return new ImportResult(courses.size(), conflicts);
    }

    /**
     * 从 Excel 字节数据导入课表（解析 + 持久化一步完成）
     *
     * @param userId     用户标识
     * @param excelBytes Excel 文件字节
     * @return 导入后的课程列表
     */
    public List<CourseEntity> importFromExcel(String userId, byte[] excelBytes) {
        return courseParser.parseAndSaveFromExcel(userId, excelBytes, courseRepository);
    }

    /**
     * 直接保存课程列表（覆盖旧数据，只覆盖无学期课程，保留学期绑定课程）
     */
    public List<CourseEntity> saveCourses(String userId, List<CourseEntity> courses) {
        return courseRepository.replaceAllNullSemester(userId, courses);
    }

    /**
     * 仅解析课表 JSON，不保存（用于导入确认流程）
     *
     * @param userId  用户标识（仅用于日志）
     * @param jsonStr LLM 提取的结构化 JSON
     * @return 解析后的课程列表（不含 id，尚未持久化）
     */
    public List<CourseEntity> parseOnly(String userId, String jsonStr) {
        List<CourseEntity> courses = courseParser.parseFromJson(jsonStr);
        if (courses.isEmpty()) {
            log.warn("课表解析为空 | userId={}", userId);
        } else {
            courses.forEach(c -> c.setUserId(userId));
        }
        return courses;
    }

    // ==================== 查询 ====================

    /**
     * 获取用户全部课程（从 SQLite 查询）
     */
    public List<CourseEntity> getAllCourses(String userId) {
        return courseRepository.findByUserId(userId);
    }

    /**
     * 获取用户今日课程（已过滤当前教学周和单双周）
     *
     * <p>优先使用用户自身 {@link SemesterService} 计算的周次，
     * 仅当用户无学期记录时回退 {@link SemesterConfig}。
     * 查询课程时按当前学期（{@code semester_id}）隔离。</p>
     */
    public List<CourseEntity> getTodayCourses(String userId) {
        int currentWeek = resolveCurrentWeek(userId);
        int today = semesterService.getCurrentDayOfWeek();

        if (currentWeek <= 0) {
            log.debug("学期未开始，无今日课程 | userId={} | currentWeek={}", userId, currentWeek);
            return List.of();
        }

        List<CourseEntity> dayCourses = findSemesterScopedCourses(userId);
        return dayCourses.stream()
                .filter(c -> c.getDayOfWeek() == today)
                .filter(c -> c.isActiveInWeek(currentWeek))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定星期几的课程
     */
    public List<CourseEntity> getCoursesByDay(String userId, int dayOfWeek) {
        int currentWeek = resolveCurrentWeek(userId);
        if (currentWeek <= 0) return List.of();

        List<CourseEntity> courses = findSemesterScopedCourses(userId);
        return courses.stream()
                .filter(c -> c.getDayOfWeek() == dayOfWeek)
                .filter(c -> c.isActiveInWeek(currentWeek))
                .collect(Collectors.toList());
    }

    /**
     * 获取今日空闲时间段
     */
    public List<TimeSlot> getFreeTimeSlots(String userId) {
        List<CourseEntity> todayCourses = getTodayCourses(userId);

        boolean[] occupied = new boolean[MAX_PERIODS + 1];
        for (CourseEntity c : todayCourses) {
            for (int p = c.getStartPeriod(); p <= c.getEndPeriod() && p <= MAX_PERIODS; p++) {
                occupied[p] = true;
            }
        }

        List<TimeSlot> freeSlots = new ArrayList<>();
        int i = 1;
        while (i <= MAX_PERIODS) {
            if (!occupied[i]) {
                int start = i;
                while (i <= MAX_PERIODS && !occupied[i]) {
                    i++;
                }
                int end = i - 1;
                freeSlots.add(new TimeSlot(start, end));
            } else {
                i++;
            }
        }

        return freeSlots;
    }

    /**
     * 获取下周课程列表
     */
    public List<CourseEntity> getNextWeekCourses(String userId) {
        int currentWeek = resolveCurrentWeek(userId);
        int nextWeek = currentWeek + 1;
        List<CourseEntity> courses = findSemesterScopedCourses(userId);
        return courses.stream()
                .filter(c -> c.isActiveInWeek(nextWeek))
                .collect(Collectors.toList());
    }

    /**
     * 获取用户课程总数
     */
    public int getCourseCount(String userId) {
        return courseRepository.countByUserId(userId);
    }

    /**
     * 删除用户全部课表
     */
    public void deleteAll(String userId) {
        courseRepository.deleteByUserId(userId);
    }

    /**
     * 更新单条课程
     */
    public boolean updateCourse(CourseEntity course) {
        if (course.getId() == null || course.getUserId() == null) {
            return false;
        }
        return courseRepository.update(course);
    }

    /**
     * 删除单条课程（含 userId 归属校验）
     */
    public boolean deleteCourse(Long id, String userId) {
        return courseRepository.deleteById(id, userId);
    }

    /**
     * 根据 ID 查询课程
     */
    public CourseEntity findCourseById(Long id) {
        return courseRepository.findById(id);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 解析用户当前教学周
     *
     * <p>优先使用用户自身的 {@link SemesterService#getCurrentWeek(String)} 计算结果；
     * 当用户无学期记录或学期未开始时，回退到 {@link SemesterConfig#getCurrentWeek()}。
     *
     * @param userId 用户标识
     * @return 当前教学周（>0 表示学期进行中）
     */
    private int resolveCurrentWeek(String userId) {
        int week = semesterService.getCurrentWeek(userId);
        if (week > 0) {
            return week;
        }
        // Fallback: 系统默认配置
        return semesterConfig.getCurrentWeek();
    }

    /**
     * 按学期范围获取用户课程
     *
     * <p>优先按当前学期（{@code semester_id}）查询课程，避免不同学期课程混淆。
     * 仅当用户无学期记录时回退到全量查询（兼容旧数据）。
     *
     * @param userId 用户标识
     * @return 课程列表
     */
    private List<CourseEntity> findSemesterScopedCourses(String userId) {
        Optional<SemesterEntity> semester = semesterService.getCurrentSemester(userId);
        if (semester.isPresent()) {
            return courseRepository.findByUserIdAndSemester(userId, semester.get().getId());
        }
        // Fallback: 无学期记录时查询全部（兼容 semester_id 为 null 的旧数据）
        return courseRepository.findByUserId(userId);
    }

    // ==================== 内部类 ====================

    /**
     * 课程冲突信息
     *
     * @param existingCourse 数据库中已有的课程
     * @param newCourse      待导入的新课程
     * @param description    冲突描述文本，如"高等数学(1-2节) 与 数据结构(1-2节) 在周一冲突"
     */
    public record ConflictInfo(
            CourseEntity existingCourse,
            CourseEntity newCourse,
            String description
    ) {}

    /**
     * 导入结果
     *
     * @param count     导入的课程数量
     * @param conflicts 冲突列表（可能为空）
     */
    public record ImportResult(int count, List<ConflictInfo> conflicts) {}

    /**
     * 空闲时间段
     *
     * @param startPeriod 开始节次
     * @param endPeriod   结束节次
     */
    public record TimeSlot(int startPeriod, int endPeriod) {

        public String display() {
            return startPeriod == endPeriod
                    ? "第" + startPeriod + "节"
                    : "第" + startPeriod + "-" + endPeriod + "节";
        }
    }

    // ==================== 冲突检测 ====================

    /**
     * 检测新课程列表与数据库中已有课程的时间冲突
     *
     * @param userId     用户 ID
     * @param newCourses 待导入的新课程列表（尚未持久化）
     * @return 冲突列表，按冲突严重程度（时间段重合度）降序排列
     */
    public List<ConflictInfo> detectConflicts(String userId, List<CourseEntity> newCourses) {
        if (newCourses == null || newCourses.isEmpty()) {
            return List.of();
        }

        List<CourseEntity> existingCourses = courseRepository.findByUserId(userId);
        if (existingCourses.isEmpty()) {
            return List.of();
        }

        List<ConflictInfo> conflicts = new ArrayList<>();

        for (CourseEntity newCourse : newCourses) {
            for (CourseEntity existing : existingCourses) {
                if (isTimeConflict(existing, newCourse)) {
                    String desc = String.format("%s(第%s节) 与 %s(第%s节) 在%s",
                            existing.getCourseName(), existing.getPeriodDisplay(),
                            newCourse.getCourseName(), newCourse.getPeriodDisplay(),
                            existing.getDayDisplay());
                    conflicts.add(new ConflictInfo(existing, newCourse, desc));
                }
            }
        }

        return conflicts;
    }

    /**
     * 判断两门课是否存在时间冲突
     *
     * <p>必须同时满足：
     * <ol>
     *   <li>同一星期几</li>
     *   <li>时间段有重叠（含端点）</li>
     *   <li>周次有交集，且在交集内至少有一周两门课都激活</li>
     * </ol>
     */
    private boolean isTimeConflict(CourseEntity a, CourseEntity b) {
        // 1. 同一天
        if (a.getDayOfWeek() != b.getDayOfWeek()) {
            return false;
        }

        // 2. 时间段重叠：[a.start, a.end] ∩ [b.start, b.end] ≠ ∅
        boolean periodOverlap = a.getStartPeriod() <= b.getEndPeriod()
                && b.getStartPeriod() <= a.getEndPeriod();
        if (!periodOverlap) {
            return false;
        }

        // 3. 周次有交集
        int maxStart = Math.max(a.getStartWeek(), b.getStartWeek());
        int minEnd = Math.min(a.getEndWeek(), b.getEndWeek());
        if (maxStart > minEnd) {
            return false;
        }

        // 4. 在共同周次范围内，找至少一周两门课都激活
        for (int week = maxStart; week <= minEnd; week++) {
            if (a.isActiveInWeek(week) && b.isActiveInWeek(week)) {
                return true;
            }
        }

        return false;
    }
}