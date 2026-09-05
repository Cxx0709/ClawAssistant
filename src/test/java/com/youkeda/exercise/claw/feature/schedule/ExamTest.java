package com.youkeda.exercise.claw.feature.schedule;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;

class ExamTest {

    @Nested
    @DisplayName("ExamEntity 实体方法")
    class EntityTest {

        @Test @DisplayName("日期格式化为 7月15日（周三）")
        void dateDisplay() {
            var e = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            assertTrue(e.getDateDisplay().contains("7月15日"));
            assertTrue(e.getDateDisplay().contains("周三"));
        }

        @Test @DisplayName("考试类型中文显示")
        void examTypeDisplay() {
            var f = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            assertEquals("期末考试", f.getExamTypeDisplay());
            var m = new ExamEntity("u1", "英语", "2026-07-10", "14:00", "16:00", "B201", "MIDTERM");
            assertEquals("期中考试", m.getExamTypeDisplay());
        }

        @Test @DisplayName("历史考试不是 upcoming")
        void pastNotUpcoming() {
            var e = new ExamEntity("u1", "高数", "2020-01-01", "08:00", "10:00", "A101", "FINAL");
            assertFalse(e.isUpcoming());
        }

        @Test @DisplayName("时间段显示")
        void timeDisplay() {
            var e = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            assertEquals("08:00-10:00", e.getTimeDisplay());
        }

        @Test @DisplayName("日期转星期几")
        void dayOfWeek() {
            var e = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            assertEquals(3, e.getDayOfWeek()); // 2026-07-15 is Wednesday
        }

        @Test @DisplayName("isWithinDays 判断")
        void withinDays() {
            var future = LocalDate.now().plusDays(5).toString();
            var e = new ExamEntity("u1", "高数", future, "08:00", "10:00", "A101", "FINAL");
            assertTrue(e.isWithinDays(10));
            assertFalse(e.isWithinDays(3));
        }
    }

    @Nested @DisplayName("ExamService 业务")
    class ServiceTest {
        private ExamRepository mockRepo;
        private ExamService svc;

        @BeforeEach
        void setUp() { mockRepo = mock(ExamRepository.class); svc = new ExamService(mockRepo); }

        @Test @DisplayName("获取全部考试")
        void getAll() {
            var e = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            when(mockRepo.findByUserId("u1")).thenReturn(List.of(e));
            assertEquals(1, svc.getAllExams("u1").size());
        }

        @Test @DisplayName("删除全部")
        void delAll() { svc.deleteAll("u1"); verify(mockRepo).deleteByUserId("u1"); }

        @Test @DisplayName("保存列表")
        void save() {
            var e = new ExamEntity("u1", "高数", "2026-07-15", "08:00", "10:00", "A101", "FINAL");
            when(mockRepo.replaceAll(eq("u1"), anyList())).thenReturn(List.of(e));
            assertFalse(svc.saveExams("u1", List.of(e)).isEmpty());
            verify(mockRepo).replaceAll(eq("u1"), anyList());
        }

        @Test @DisplayName("未来 N 天考试")
        void withinDays() {
            var d = LocalDate.now().plusDays(5).toString();
            var e = new ExamEntity("u1", "高数", d, "08:00", "10:00", "A101", "FINAL");
            when(mockRepo.findUpcoming(eq("u1"), anyString())).thenReturn(List.of(e));
            assertEquals(1, svc.getExamsWithinDays("u1", 10).size());
        }

        @Test @DisplayName("看板只返回今天起 30 天内考试")
        void upcomingThirtyDayWindow() {
            var today = new ExamEntity("u1", "今天考试", LocalDate.now().toString(), "08:00", "10:00", "A101", "FINAL");
            var day30 = new ExamEntity("u1", "第30天考试", LocalDate.now().plusDays(30).toString(), "08:00", "10:00", "A101", "FINAL");
            var day31 = new ExamEntity("u1", "第31天考试", LocalDate.now().plusDays(31).toString(), "08:00", "10:00", "A101", "FINAL");
            when(mockRepo.findUpcoming(eq("u1"), anyString())).thenReturn(List.of(today, day30, day31));

            assertEquals(List.of(today, day30), svc.getUpcomingExams("u1"));
        }
    }
}
