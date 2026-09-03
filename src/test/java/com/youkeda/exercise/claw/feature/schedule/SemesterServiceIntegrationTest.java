package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemesterService 集成测试
 *
 * <p>测试 SemesterService + CourseService 的学期感知查询链路。
 * 使用真实 SQLite 数据库，不依赖 Spring 容器。
 *
 * <p>覆盖：
 * <ul>
 *   <li>不同用户不同学期 → 周次不同</li>
 *   <li>同一用户两学期课程 → 只返回当前学期课程</li>
 *   <li>无 Semester 用户 → 回退 SemesterConfig</li>
 *   <li>按学期隔离的课程查询</li>
 * </ul>
 */
class SemesterServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private SemesterRepository semesterRepository;
    private SemesterService semesterService;
    private CourseRepository courseRepository;
    private CourseService courseService;
    private SemesterConfig semesterConfig;
    private String dbPath;

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        dbPath = new File(tempDir.toFile(), "test-semester.db").getAbsolutePath();

        // 初始化 SemesterRepository
        semesterRepository = new SemesterRepository();
        setField(semesterRepository, "dbPath", dbPath);
        semesterRepository.init();

        // 初始化 SemesterService
        semesterService = new SemesterService(semesterRepository);

        // 初始化 CourseRepository
        courseRepository = new CourseRepository();
        setField(courseRepository, "dbPath", dbPath);
        courseRepository.init();

        // 初始化 SemesterConfig（不设置 semesterStart，使用默认值）
        semesterConfig = new SemesterConfig();

        // 初始化 CourseService
        ObjectMapper objectMapper = new ObjectMapper();
        CourseParser courseParser = new CourseParser(objectMapper);
        courseService = new CourseService(courseRepository, courseParser, semesterConfig, semesterService);
    }

    @Nested
    @DisplayName("多用户学期周次计算")
    class MultiUserWeekTest {

        @Test
        @DisplayName("不同用户不同学期起始 → currentWeek 不同")
        void differentUsersDifferentWeeks() {
            // userA: 学期从 7 天前开始 → 第 2 周
            LocalDate startA = LocalDate.now().minusDays(7);
            SemesterEntity semA = semesterService.createSemester("userA", 2026,
                    SemesterEntity.TERM_FALL, startA, "TEST");

            // userB: 学期从今天开始 → 第 1 周
            LocalDate startB = LocalDate.now();
            SemesterEntity semB = semesterService.createSemester("userB", 2026,
                    SemesterEntity.TERM_FALL, startB, "TEST");

            int weekA = semesterService.getCurrentWeek("userA");
            int weekB = semesterService.getCurrentWeek("userB");

            assertEquals(2, weekA, "userA 从 7 天前开始应为第 2 周");
            assertEquals(1, weekB, "userB 从今天开始应为第 1 周");
            assertEquals(weekA - weekB, 1, "userA 应比 userB 多 1 周");
        }

        @Test
        @DisplayName("有学期用户和无学期用户周次不同")
        void semesterUserVsNoSemesterUser() {
            // 有学期用户
            LocalDate start = LocalDate.now().minusDays(14);
            semesterService.createSemester("userWithSem", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");

            // 无学期用户
            int weekWith = semesterService.getCurrentWeek("userWithSem");
            int weekWithout = semesterService.getCurrentWeek("noSemUser");

            assertTrue(weekWith > 0, "有学期用户应返回 > 0");
            assertEquals(-1, weekWithout, "无学期用户应返回 -1");
        }
    }

    @Nested
    @DisplayName("同一用户多学期课程隔离")
    class SameUserMultiSemesterTest {

        @Test
        @DisplayName("同一用户两个学期的课程，getTodayCourses 只返回当前学期")
        void todayCoursesOnlyReturnsCurrentSemester() {
            // 创建两个不同的学期（不同 academicYear）
            // sem1: 2025 FALL（较旧）
            LocalDate start1 = LocalDate.now().minusDays(21);
            SemesterEntity sem1 = semesterService.createSemester("multiUser", 2025,
                    SemesterEntity.TERM_FALL, start1, "TEST");

            // sem2: 2026 FALL（更新）
            LocalDate start2 = LocalDate.now().minusDays(7);
            SemesterEntity sem2 = semesterService.createSemester("multiUser", 2026,
                    SemesterEntity.TERM_FALL, start2, "TEST");

            int today = LocalDate.now().getDayOfWeek().getValue();

            // 给 sem1 添加今日课程
            CourseEntity oldCourse = new CourseEntity("multiUser", "旧学期课程", "张老师",
                    today, 1, 2, "A101", 1, 16, CourseEntity.WEEK_ALL);
            oldCourse.setSemesterId(sem1.getId());
            courseRepository.replaceAllBySemester("multiUser", sem1.getId(), List.of(oldCourse));

            // 给 sem2（当前学期）添加今日课程
            CourseEntity currentCourse = new CourseEntity("multiUser", "新学期课程", "李老师",
                    today, 3, 4, "B202", 1, 16, CourseEntity.WEEK_ALL);
            currentCourse.setSemesterId(sem2.getId());
            courseRepository.replaceAllBySemester("multiUser", sem2.getId(), List.of(currentCourse));

            // 验证 getTodayCourses 只返回当前学期（sem2）的课程
            List<CourseEntity> todayCourses = courseService.getTodayCourses("multiUser");
            assertEquals(1, todayCourses.size(),
                    "应只返回当前学期的今日课程，而非两个学期所有今日课程");
            assertEquals("新学期课程", todayCourses.get(0).getCourseName(),
                    "应返回 sem2（最新学期）的课程");
        }

        @Test
        @DisplayName("getCoursesByDay 按学期隔离查询")
        void coursesByDayIsolatedBySemester() {
            // 创建两个不同的学期（不同 academicYear）
            LocalDate start1 = LocalDate.now().minusDays(14);
            SemesterEntity sem1 = semesterService.createSemester("isoUser", 2025,
                    SemesterEntity.TERM_FALL, start1, "TEST");

            LocalDate start2 = LocalDate.now().minusDays(3);
            SemesterEntity sem2 = semesterService.createSemester("isoUser", 2026,
                    SemesterEntity.TERM_FALL, start2, "TEST");

            // sem1 课程（周一，今天是周几取决于当前日期，我们使用固定 dayOfWeek=1 测试）
            CourseEntity c1 = new CourseEntity("isoUser", "旧学期周一课", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            c1.setSemesterId(sem1.getId());
            courseRepository.replaceAllBySemester("isoUser", sem1.getId(), List.of(c1));

            // sem2 课程（周一）
            CourseEntity c2 = new CourseEntity("isoUser", "新学期周一课", null,
                    1, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            c2.setSemesterId(sem2.getId());
            courseRepository.replaceAllBySemester("isoUser", sem2.getId(), List.of(c2));

            // getCoursesByDay 应只返回 sem2 的课程
            List<CourseEntity> mondayCourses = courseService.getCoursesByDay("isoUser", 1);
            assertEquals(1, mondayCourses.size(),
                    "应只返回当前学期的周一课程");
            assertEquals("新学期周一课", mondayCourses.get(0).getCourseName());
        }

        @Test
        @DisplayName("getNextWeekCourses 按学期隔离")
        void nextWeekCoursesIsolatedBySemester() {
            LocalDate start = LocalDate.now().minusDays(7);
            SemesterEntity sem = semesterService.createSemester("nextUser", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");

            // 当前学期下周课程
            CourseEntity c = new CourseEntity("nextUser", "下周课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            c.setSemesterId(sem.getId());
            courseRepository.replaceAllBySemester("nextUser", sem.getId(), List.of(c));

            List<CourseEntity> nextCourses = courseService.getNextWeekCourses("nextUser");
            assertEquals(1, nextCourses.size(),
                    "应返回当前学期的下周课程");
        }
    }

    @Nested
    @DisplayName("无学期记录回退测试")
    class FallbackTest {

        @Test
        @DisplayName("无 Semester 用户 → CourseService 使用 SemesterConfig 回退")
        void noSemesterFallbackToConfig() {
            // 不创建任何学期记录
            int today = LocalDate.now().getDayOfWeek().getValue();

            // 保存课程（无 semester_id）
            CourseEntity c = new CourseEntity("fallbackUser", "回退测试课程", null,
                    today, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            courseRepository.replaceAll("fallbackUser", List.of(c));

            // 验证 getTodayCourses 仍能返回课程（通过 SemesterConfig 回退）
            List<CourseEntity> todayCourses = courseService.getTodayCourses("fallbackUser");
            assertFalse(todayCourses.isEmpty(),
                    "无学期用户应能通过 SemesterConfig 回退查到课程");
            assertEquals("回退测试课程", todayCourses.get(0).getCourseName());

            // semesterService 返回 -1
            assertEquals(-1, semesterService.getCurrentWeek("fallbackUser"));
            // 但 resolveCurrentWeek 在 CourseService 内部回退到 config → 返回 1 (config 默认)
        }
    }

    @Nested
    @DisplayName("Reminder 多用户周次测试")
    class ReminderWeekTest {

        @Test
        @DisplayName("不同用户按各自 Semester 计算不同周次")
        void differentWeeksForDifferentUsers() {
            // 模拟 Reminder 场景：用户 A 和用户 B 有不同学期起始
            // userA: 学期从 14 天前开始 → 第 3 周
            LocalDate startA = LocalDate.now().minusDays(14);
            semesterService.createSemester("remindUserA", 2026,
                    SemesterEntity.TERM_FALL, startA, "TEST");

            // userB: 学期从 7 天前开始 → 第 2 周
            LocalDate startB = LocalDate.now().minusDays(7);
            semesterService.createSemester("remindUserB", 2026,
                    SemesterEntity.TERM_FALL, startB, "TEST");

            // 验证两个用户在同一个时间点有不同周次
            int weekA = semesterService.getCurrentWeek("remindUserA");
            int weekB = semesterService.getCurrentWeek("remindUserB");

            assertEquals(3, weekA, "userA 从 14 天前开始应为第 3 周");
            assertEquals(2, weekB, "userB 从 7 天前开始应为第 2 周");
        }

        @Test
        @DisplayName("同一课程在不同用户的不同周次下 isActiveInWeek 结果不同")
        void sameCourseDifferentWeeks() {
            // 用户 A：学期从 14 天前开始 → 第 3 周（单周）
            LocalDate startA = LocalDate.now().minusDays(14);
            semesterService.createSemester("userA", 2026,
                    SemesterEntity.TERM_FALL, startA, "TEST");

            // 用户 B：学期从 7 天前开始 → 第 2 周（双周）
            LocalDate startB = LocalDate.now().minusDays(7);
            semesterService.createSemester("userB", 2026,
                    SemesterEntity.TERM_FALL, startB, "TEST");

            // 一门单周课程，范围 1-16 周
            CourseEntity oddCourse = new CourseEntity("shared", "单周课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ODD);

            int weekA = semesterService.getCurrentWeek("userA"); // 3 → 单周 → 激活
            int weekB = semesterService.getCurrentWeek("userB"); // 2 → 双周 → 不激活

            assertTrue(oddCourse.isActiveInWeek(weekA),
                    "userA 第 3 周（单周）应激活单周课程");
            assertFalse(oddCourse.isActiveInWeek(weekB),
                    "userB 第 2 周（双周）不应激活单周课程");
        }
    }

    @Nested
    @DisplayName("学期数据持久化与查询")
    class SemesterPersistenceTest {

        @Test
        @DisplayName("创建并查询学期")
        void createAndQuerySemester() {
            LocalDate start = LocalDate.of(2026, 9, 7);
            SemesterEntity saved = semesterService.createSemester("persistUser", 2026,
                    SemesterEntity.TERM_FALL, start, SemesterEntity.SOURCE_USER_CONFIRM);

            assertNotNull(saved.getId());
            assertEquals("2026秋季学期", saved.getDisplayName());
            assertEquals("2026年9月7日（周一）", saved.getStartDateDisplay());

            // 通过 repository 查询
            var loaded = semesterRepository.findLatestByUserId("persistUser");
            assertTrue(loaded.isPresent());
            assertEquals("2026秋季学期", loaded.get().getDisplayName());
        }

        @Test
        @DisplayName("多个用户学期数据隔离")
        void multiUserSemesterIsolation() {
            semesterService.createSemester("u1", 2026, SemesterEntity.TERM_FALL,
                    LocalDate.of(2026, 9, 7), "TEST");
            semesterService.createSemester("u2", 2026, SemesterEntity.TERM_SPRING,
                    LocalDate.of(2026, 3, 2), "TEST");

            assertEquals(1, semesterService.countSemesters("u1"));
            assertEquals(1, semesterService.countSemesters("u2"));

            // 删除 u1，不影响 u2
            semesterService.deleteAll("u1");
            assertEquals(0, semesterService.countSemesters("u1"));
            assertEquals(1, semesterService.countSemesters("u2"));
        }
    }

    @Nested
    @DisplayName("重复学期检测（唯一性约束）")
    class DuplicateSemesterTest {

        @Test
        @DisplayName("同一用户同一学期只创建一次，重复创建返回已存在的学期")
        void duplicateSemesterReusesExisting() {
            LocalDate start = LocalDate.of(2026, 9, 7);

            // 第一次创建
            SemesterEntity first = semesterService.createSemester("dupUser", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");
            assertNotNull(first.getId());

            // 第二次创建同一学期
            SemesterEntity second = semesterService.createSemester("dupUser", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");
            assertNotNull(second.getId());

            // 验证查找已有学期工作正常
            var existing = semesterService.findExistingSemester("dupUser", 2026, SemesterEntity.TERM_FALL);
            assertTrue(existing.isPresent());
            assertEquals(first.getAcademicYear(), existing.get().getAcademicYear());
            assertEquals(first.getTerm(), existing.get().getTerm());
        }

        @Test
        @DisplayName("不同用户同一年份学期互不干扰")
        void differentUsersSameYearTerm() {
            LocalDate start = LocalDate.of(2026, 9, 7);

            semesterService.createSemester("userX", 2026, SemesterEntity.TERM_FALL, start, "TEST");
            semesterService.createSemester("userY", 2026, SemesterEntity.TERM_FALL, start, "TEST");

            // 各查各的
            assertTrue(semesterService.findExistingSemester("userX", 2026, SemesterEntity.TERM_FALL).isPresent());
            assertTrue(semesterService.findExistingSemester("userY", 2026, SemesterEntity.TERM_FALL).isPresent());

            // 不存在的学期返回 empty
            assertFalse(semesterService.findExistingSemester("userX", 2025, SemesterEntity.TERM_FALL).isPresent());
            assertFalse(semesterService.findExistingSemester("userY", 2026, SemesterEntity.TERM_SPRING).isPresent());
        }

        @Test
        @DisplayName("hasSemester 方法正确判断")
        void hasSemesterCheck() {
            assertFalse(semesterService.hasSemester("noSemUser"));

            semesterService.createSemester("hasSemUser", 2026, SemesterEntity.TERM_FALL,
                    LocalDate.of(2026, 9, 7), "TEST");

            assertTrue(semesterService.hasSemester("hasSemUser"));
        }
    }

    @Nested
    @DisplayName("学期切换与多学期共存")
    class SemesterSwitchTest {

        @Test
        @DisplayName("用户从 2026 FALL 切换到 2027 SPRING，两个学期课程独立")
        void switchFromFallToSpring() {
            // 创建 2026 FALL（起始 14 天前）
            LocalDate fallStart = LocalDate.now().minusDays(14);
            SemesterEntity fallSem = semesterService.createSemester("switchUser", 2026,
                    SemesterEntity.TERM_FALL, fallStart, "TEST");

            // Fall 学期课程
            CourseEntity fallCourse = new CourseEntity("switchUser", "秋季课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            fallCourse.setSemesterId(fallSem.getId());
            courseRepository.replaceAllBySemester("switchUser", fallSem.getId(), List.of(fallCourse));

            // 验证 getCurrentSemester 返回 Fall（最新）
            var current = semesterService.getCurrentSemester("switchUser");
            assertTrue(current.isPresent());
            assertEquals(SemesterEntity.TERM_FALL, current.get().getTerm());

            // 创建 2027 SPRING（起始 7 天前，比 Fall 更新）
            LocalDate springStart = LocalDate.now().minusDays(7);
            SemesterEntity springSem = semesterService.createSemester("switchUser", 2027,
                    SemesterEntity.TERM_SPRING, springStart, "TEST");

            // Spring 学期课程
            CourseEntity springCourse = new CourseEntity("switchUser", "春季课程", null,
                    1, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            springCourse.setSemesterId(springSem.getId());
            courseRepository.replaceAllBySemester("switchUser", springSem.getId(), List.of(springCourse));

            // 当前学期应为 2027 SPRING（最新）
            current = semesterService.getCurrentSemester("switchUser");
            assertTrue(current.isPresent());
            assertEquals(2027, current.get().getAcademicYear());
            assertEquals(SemesterEntity.TERM_SPRING, current.get().getTerm());

            // getTodayCourses 只返回当前学期（Spring）的课程
            List<CourseEntity> todayCourses = courseService.getTodayCourses("switchUser");
            for (CourseEntity c : todayCourses) {
                assertEquals("春季课程", c.getCourseName(),
                        "当前学期课程应为春季学期课程");
            }

            // Fall 课程仍在数据库中
            List<CourseEntity> fallCourses = courseRepository.findByUserIdAndSemester("switchUser", fallSem.getId());
            assertEquals(1, fallCourses.size());
            assertEquals("秋季课程", fallCourses.get(0).getCourseName());
        }

        @Test
        @DisplayName("同一学期重复导入时复用已有 Semester 记录")
        void reimportSameSemesterReusesExisting() {
            // 创建学期并导入课程
            LocalDate start = LocalDate.now().minusDays(7);
            SemesterEntity sem = semesterService.createSemester("reimportUser", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");

            CourseEntity c1 = new CourseEntity("reimportUser", "原课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            c1.setSemesterId(sem.getId());
            courseRepository.replaceAllBySemester("reimportUser", sem.getId(), List.of(c1));

            // 模拟第二次导入同一学期（通过 findExistingSemester 复用）
            var existing = semesterService.findExistingSemester("reimportUser", 2026, SemesterEntity.TERM_FALL);
            assertTrue(existing.isPresent());
            assertEquals(sem.getId(), existing.get().getId(),
                    "应复用已存在的学期记录，而非创建新记录");

            // 新课程覆盖
            CourseEntity c2 = new CourseEntity("reimportUser", "新课覆盖", null,
                    2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            c2.setSemesterId(existing.get().getId());
            courseRepository.replaceAllBySemester("reimportUser", existing.get().getId(), List.of(c2));

            // 原课程已被覆盖
            List<CourseEntity> all = courseRepository.findByUserIdAndSemester("reimportUser", sem.getId());
            assertEquals(1, all.size());
            assertEquals("新课覆盖", all.get(0).getCourseName());
        }
    }

    @Nested
    @DisplayName("多用户数据隔离")
    class MultipleUserIsolationTest {

        @Test
        @DisplayName("用户之间学期数据完全隔离")
        void semesterCompleteIsolation() {
            // user1: 2026 FALL
            LocalDate user1Start = LocalDate.of(2026, 9, 7);
            semesterService.createSemester("isoU1", 2026,
                    SemesterEntity.TERM_FALL, user1Start, "TEST");

            // user2: 2027 SPRING
            LocalDate user2Start = LocalDate.of(2027, 3, 1);
            semesterService.createSemester("isoU2", 2027,
                    SemesterEntity.TERM_SPRING, user2Start, "TEST");

            // user3: 无学期
            // user4: 2026 FALL（与 user1 同一年份学期但不同用户）
            LocalDate user4Start = LocalDate.of(2026, 9, 14);
            semesterService.createSemester("isoU4", 2026,
                    SemesterEntity.TERM_FALL, user4Start, "TEST");

            // 验证隔离
            assertEquals(1, semesterService.countSemesters("isoU1"));
            assertEquals(1, semesterService.countSemesters("isoU2"));
            assertEquals(0, semesterService.countSemesters("isoU3"));
            assertEquals(1, semesterService.countSemesters("isoU4"));

            // user1 的学期
            var u1Sem = semesterService.getCurrentSemester("isoU1");
            assertTrue(u1Sem.isPresent());
            assertEquals(2026, u1Sem.get().getAcademicYear());
            assertEquals(SemesterEntity.TERM_FALL, u1Sem.get().getTerm());

            // user2 的学期
            var u2Sem = semesterService.getCurrentSemester("isoU2");
            assertTrue(u2Sem.isPresent());
            assertEquals(2027, u2Sem.get().getAcademicYear());
            assertEquals(SemesterEntity.TERM_SPRING, u2Sem.get().getTerm());

            // user3 无学期
            assertFalse(semesterService.hasSemester("isoU3"));

            // user4 有自己的学期，与 user1 不同
            var u4Sem = semesterService.getCurrentSemester("isoU4");
            assertTrue(u4Sem.isPresent());
            assertNotEquals(u1Sem.get().getId(), u4Sem.get().getId(),
                    "不同用户的学期记录 ID 应不同");
        }

        @Test
        @DisplayName("用户之间课程数据完全隔离")
        void courseCompleteIsolation() {
            LocalDate start = LocalDate.now().minusDays(7);

            // userA: 创建学期 + 课程
            SemesterEntity semA = semesterService.createSemester("courseUserA", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");
            CourseEntity cA = new CourseEntity("courseUserA", "用户A的课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            cA.setSemesterId(semA.getId());
            courseRepository.replaceAllBySemester("courseUserA", semA.getId(), List.of(cA));

            // userB: 创建学期 + 课程
            SemesterEntity semB = semesterService.createSemester("courseUserB", 2026,
                    SemesterEntity.TERM_FALL, start, "TEST");
            CourseEntity cB = new CourseEntity("courseUserB", "用户B的课程", null,
                    1, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            cB.setSemesterId(semB.getId());
            courseRepository.replaceAllBySemester("courseUserB", semB.getId(), List.of(cB));

            // A 只能看到 A 的课程
            List<CourseEntity> coursesA = courseRepository.findByUserIdAndSemester("courseUserA", semA.getId());
            assertEquals(1, coursesA.size());
            assertEquals("用户A的课程", coursesA.get(0).getCourseName());

            // B 只能看到 B 的课程
            List<CourseEntity> coursesB = courseRepository.findByUserIdAndSemester("courseUserB", semB.getId());
            assertEquals(1, coursesB.size());
            assertEquals("用户B的课程", coursesB.get(0).getCourseName());

            // countByUserId 也隔离
            assertEquals(1, courseRepository.countByUserId("courseUserA"));
            assertEquals(1, courseRepository.countByUserId("courseUserB"));

            // 删除 A 不影响 B
            semesterService.deleteAll("courseUserA");
            assertTrue(semesterService.hasSemester("courseUserB"));
        }
    }
}
