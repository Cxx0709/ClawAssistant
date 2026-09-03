package com.youkeda.exercise.claw.feature.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 课表仓库持久化测试
 *
 * <p>测试 {@link CourseRepository} 的学期关联功能。
 * 使用临时 SQLite 数据库，不依赖 Spring 容器。
 */
class CourseRepositoryTest {

    @TempDir
    Path tempDir;

    private CourseRepository repository;

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
        repository = new CourseRepository();
        setField(repository, "dbPath", new File(tempDir.toFile(), "test-courses.db").getAbsolutePath());
        repository.init();
    }

    @Test
    @DisplayName("初始化后表已创建")
    void tableCreated() {
        // init 成功即表已创建（无异常抛出）
        assertNotNull(repository);
    }

    @Nested
    @DisplayName("Course 保存 semesterId")
    class SemesterIdPersistenceTest {

        @Test
        @DisplayName("保存课程并设置 semesterId，查询后保持一致")
        void saveCourseWithSemesterId() {
            CourseEntity course = new CourseEntity("user001", "高等数学", "张老师",
                    1, 1, 2, "A101", 1, 16, CourseEntity.WEEK_ALL);
            course.setSemesterId(100L);

            List<CourseEntity> saved = repository.replaceAll("user001", List.of(course));
            assertEquals(1, saved.size());
            assertEquals("高等数学", saved.get(0).getCourseName());
            assertEquals(Long.valueOf(100L), saved.get(0).getSemesterId());
        }

        @Test
        @DisplayName("semesterId 未设置时保持 null")
        void saveCourseWithoutSemesterId() {
            CourseEntity course = new CourseEntity("user001", "大学英语", "李老师",
                    3, 3, 4, "B202", 1, 16, CourseEntity.WEEK_ALL);

            List<CourseEntity> saved = repository.replaceAll("user001", List.of(course));
            assertEquals(1, saved.size());
            assertNull(saved.get(0).getSemesterId());
        }
    }

    @Nested
    @DisplayName("同一用户不同学期课程共存")
    class MultiSemesterTest {

        @Test
        @DisplayName("同一用户两个学期的课程共存，互不影响")
        void coursesFromDifferentSemestersCoexist() {
            // 2026 春季学期（semesterId=1）
            CourseEntity springCourse = new CourseEntity("user002", "高等数学", "张老师",
                    1, 1, 2, "A101", 1, 16, CourseEntity.WEEK_ALL);
            springCourse.setSemesterId(1L);
            List<CourseEntity> springSaved = repository.replaceAllBySemester("user002", 1L, List.of(springCourse));
            assertEquals(1, springSaved.size());

            // 2026 秋季学期（semesterId=2）
            CourseEntity fallCourse = new CourseEntity("user002", "大学物理", "王老师",
                    1, 3, 4, "C201", 1, 16, CourseEntity.WEEK_ALL);
            fallCourse.setSemesterId(2L);
            List<CourseEntity> fallSaved = repository.replaceAllBySemester("user002", 2L, List.of(fallCourse));
            assertEquals(1, fallSaved.size());

            // 验证两个学期的课程都能查到
            List<CourseEntity> springCourses = repository.findByUserIdAndSemester("user002", 1L);
            List<CourseEntity> fallCourses = repository.findByUserIdAndSemester("user002", 2L);

            assertEquals(1, springCourses.size());
            assertEquals("高等数学", springCourses.get(0).getCourseName());
            assertEquals(Long.valueOf(1L), springCourses.get(0).getSemesterId());

            assertEquals(1, fallCourses.size());
            assertEquals("大学物理", fallCourses.get(0).getCourseName());
            assertEquals(Long.valueOf(2L), fallCourses.get(0).getSemesterId());

            // findAll 返回两条
            assertEquals(2, repository.findByUserId("user002").size());
        }

        @Test
        @DisplayName("旧 findAll 可以同时查到多个学期的课程")
        void findAllReturnsAllSemesters() {
            CourseEntity c1 = new CourseEntity("user003", "课程A", null, 1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            c1.setSemesterId(1L);
            repository.replaceAllBySemester("user003", 1L, List.of(c1));

            CourseEntity c2 = new CourseEntity("user003", "课程B", null, 2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            c2.setSemesterId(2L);
            repository.replaceAllBySemester("user003", 2L, List.of(c2));

            List<CourseEntity> all = repository.findByUserId("user003");
            assertEquals(2, all.size());
        }
    }

    @Nested
    @DisplayName("replaceAllBySemester 隔离删除")
    class ReplaceAllBySemesterTest {

        @Test
        @DisplayName("只删除目标学期的课程，不影响其他学期")
        void replaceOnlyTargetSemester() {
            // 先创建两个学期的课程
            CourseEntity springCourse = new CourseEntity("user004", "春季课", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            springCourse.setSemesterId(10L);
            repository.replaceAllBySemester("user004", 10L, List.of(springCourse));

            CourseEntity fallCourse = new CourseEntity("user004", "秋季课", null,
                    2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            fallCourse.setSemesterId(11L);
            repository.replaceAllBySemester("user004", 11L, List.of(fallCourse));

            // 现在替换秋季学期（semesterId=11）的数据
            CourseEntity newFallCourse = new CourseEntity("user004", "新秋季课", null,
                    3, 5, 6, null, 1, 16, CourseEntity.WEEK_ALL);
            newFallCourse.setSemesterId(11L);
            List<CourseEntity> replaced = repository.replaceAllBySemester("user004", 11L, List.of(newFallCourse));

            // 秋季学期只剩一门新课
            assertEquals(1, replaced.size());
            assertEquals("新秋季课", replaced.get(0).getCourseName());

            // 春季学期不受影响
            List<CourseEntity> springCourses = repository.findByUserIdAndSemester("user004", 10L);
            assertEquals(1, springCourses.size());
            assertEquals("春季课", springCourses.get(0).getCourseName());

            // 用户总课程数仍然为 2
            assertEquals(2, repository.countByUserId("user004"));
        }

        @Test
        @DisplayName("空课程列表时清空该学期课程")
        void emptyListClearsSemester() {
            CourseEntity course = new CourseEntity("user005", "要删除的课", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            course.setSemesterId(20L);
            repository.replaceAllBySemester("user005", 20L, List.of(course));

            // 空列表替换 → 应清空
            repository.replaceAllBySemester("user005", 20L, List.of());

            List<CourseEntity> courses = repository.findByUserIdAndSemester("user005", 20L);
            assertTrue(courses.isEmpty());
        }
    }

    @Nested
    @DisplayName("replaceAllNullSemester 无学期覆盖导入")
    class ReplaceAllNullSemesterTest {

        @Test
        @DisplayName("只删除无学期课程，不影响学期绑定课程")
        void replaceOnlyNullSemester() {
            // 先建一门学期绑定课程（semesterId=50）
            CourseEntity semCourse = new CourseEntity("user010", "学期课", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            semCourse.setSemesterId(50L);
            repository.replaceAllBySemester("user010", 50L, List.of(semCourse));

            // 再建一门无学期旧课程（用 replaceAllNullSemester，避免 replaceAll 误删学期课）
            CourseEntity oldNull = new CourseEntity("user010", "旧无学期课", null,
                    2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            repository.replaceAllNullSemester("user010", List.of(oldNull));

            // 无学期覆盖导入（即使传入带 semesterId 的课程，也应强制置 null）
            CourseEntity newNull = new CourseEntity("user010", "新无学期课", null,
                    3, 5, 6, null, 1, 16, CourseEntity.WEEK_ALL);
            newNull.setSemesterId(999L);
            List<CourseEntity> replaced = repository.replaceAllNullSemester("user010", List.of(newNull));

            assertEquals(1, replaced.size());
            assertEquals("新无学期课", replaced.get(0).getCourseName());
            assertNull(replaced.get(0).getSemesterId());

            // 学期绑定课程不受影响
            List<CourseEntity> semCourses = repository.findByUserIdAndSemester("user010", 50L);
            assertEquals(1, semCourses.size());
            assertEquals("学期课", semCourses.get(0).getCourseName());

            // 用户总课程数为 2（学期课 + 新无学期课）
            assertEquals(2, repository.countByUserId("user010"));
        }

        @Test
        @DisplayName("空列表时清空该用户无学期课程")
        void emptyListClearsNullSemester() {
            CourseEntity course = new CourseEntity("user011", "要清空的课", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            repository.replaceAllNullSemester("user011", List.of(course));

            repository.replaceAllNullSemester("user011", List.of());

            assertEquals(0, repository.countByUserId("user011"));
        }
    }

    @Nested
    @DisplayName("旧数据兼容（semester_id = NULL）")
    class LegacyDataCompatibilityTest {

        @Test
        @DisplayName("旧 replaceAll 方法保存的数据 semester_id 为 null")
        void legacyReplaceAllReturnsNullSemesterId() {
            CourseEntity course = new CourseEntity("user006", "旧课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);

            List<CourseEntity> saved = repository.replaceAll("user006", List.of(course));
            assertEquals(1, saved.size());
            assertEquals("旧课程", saved.get(0).getCourseName());
            assertNull(saved.get(0).getSemesterId());
        }

        @Test
        @DisplayName("新数据 semester_id 有值，旧数据为 null，两者共存")
        void newAndOldDataCoexist() {
            // 旧方式保存（无 semesterId）
            CourseEntity oldCourse = new CourseEntity("user007", "旧数据课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            repository.replaceAll("user007", List.of(oldCourse));

            // 新方式保存（有 semesterId）
            CourseEntity newCourse = new CourseEntity("user007", "新数据课程", null,
                    2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            newCourse.setSemesterId(30L);
            repository.replaceAllBySemester("user007", 30L, List.of(newCourse));

            // findAll 能同时查到两个
            List<CourseEntity> all = repository.findByUserId("user007");
            assertEquals(2, all.size());

            // 旧方式查询仍能查到无 semester_id 的课程
            // 注意：findByUserIdAndSemester 按 semester_id=30 只能查到新课程
            List<CourseEntity> semesterCourses = repository.findByUserIdAndSemester("user007", 30L);
            assertEquals(1, semesterCourses.size());
            assertEquals("新数据课程", semesterCourses.get(0).getCourseName());
        }

        @Test
        @DisplayName("deleteByUserIdAndSemester 不影响 semester_id 为 null 的旧数据")
        void deleteBySemesterDoesNotAffectLegacy() {
            // 旧方式
            CourseEntity oldCourse = new CourseEntity("user008", "遗留课程", null,
                    1, 1, 2, null, 1, 16, CourseEntity.WEEK_ALL);
            repository.replaceAll("user008", List.of(oldCourse));

            // 新方式
            CourseEntity newCourse = new CourseEntity("user008", "学期课程", null,
                    2, 3, 4, null, 1, 16, CourseEntity.WEEK_ALL);
            newCourse.setSemesterId(40L);
            repository.replaceAllBySemester("user008", 40L, List.of(newCourse));

            // 删除新学期的课程
            repository.deleteByUserIdAndSemester("user008", 40L);

            // 旧数据还在
            List<CourseEntity> all = repository.findByUserId("user008");
            assertEquals(1, all.size());
            assertEquals("遗留课程", all.get(0).getCourseName());
            assertNull(all.get(0).getSemesterId());
        }
    }
}