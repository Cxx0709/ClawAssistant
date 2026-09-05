package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduleReminderServiceTest {
    private final CourseRepository repository = mock(CourseRepository.class);
    private final SemesterService semesters = mock(SemesterService.class);
    private final ScheduleTimeResolver times = mock(ScheduleTimeResolver.class);
    private final CourseService courses = mock(CourseService.class);
    private final NotificationSink sink = mock(NotificationSink.class);
    private final ScheduleReminderService reminders = new ScheduleReminderService(repository,
            new SemesterConfig(), sink, semesters, times, courses);

    /** 默认提醒时间 07:30 之后、宽限期（120 分钟）之内的时间点 */
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 14, 7, 31);

    private void prepare() {
        CourseEntity c = new CourseEntity("u", "高数", "", 1, 1, 2, "A101", 1, 16, "ALL");
        c.setId(1L);
        when(repository.findAll()).thenReturn(List.of(c));
        when(courses.getCoursesOnDate(eq("u"), any(LocalDate.class))).thenReturn(
                new CourseService.DateCourses(now.toLocalDate(), 2, 1L, true, List.of(c)));
        when(times.formatTimeRange(eq("u"), anyInt(), anyInt())).thenReturn("08:00-09:40");
    }

    @Test void sendsOncePerDayAndAgainNextDay() {
        prepare();
        reminders.checkReminders(now);
        reminders.checkReminders(now.plusMinutes(1));
        verify(sink, times(1)).publish(eq("u"), eq("COURSE_DAILY"), eq("今日课表"),
                anyString(), eq(5), isNull());
        // 第二天同一时间再次提醒
        reminders.checkReminders(now.plusDays(1));
        verify(sink, times(2)).publish(eq("u"), eq("COURSE_DAILY"), eq("今日课表"),
                anyString(), eq(5), isNull());
    }

    @Test void skipsOutsideReminderWindow() {
        prepare();
        // 提醒时间之前
        reminders.checkReminders(now.withHour(6).withMinute(0));
        // 超过宽限期
        reminders.checkReminders(now.withHour(10).withMinute(0));
        verifyNoInteractions(sink);
    }

    @Test void noCoursesTodayIsSkipped() {
        prepare();
        when(courses.getCoursesOnDate(eq("u"), any(LocalDate.class))).thenReturn(
                new CourseService.DateCourses(now.toLocalDate(), 2, 1L, true, List.of()));
        reminders.checkReminders(now);
        verifyNoInteractions(sink);
    }

    @Test void unconfiguredSemesterIsSkipped() {
        prepare();
        when(courses.getCoursesOnDate(eq("u"), any(LocalDate.class))).thenReturn(
                new CourseService.DateCourses(now.toLocalDate(), 0, 1L, false, List.of()));
        reminders.checkReminders(now);
        verifyNoInteractions(sink);
    }

    @Test void failedDeliveryCanRetryWithinWindow() {
        prepare();
        when(sink.publish(anyString(), anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("temporary delivery failure")).thenReturn(1L);
        reminders.checkReminders(now);
        reminders.checkReminders(now.plusMinutes(1));
        verify(sink, times(2)).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), isNull());
    }

    @Test void digestMessageContainsCourseInfo() {
        prepare();
        reminders.checkReminders(now);
        verify(sink).publish(eq("u"), eq("COURSE_DAILY"), eq("今日课表"),
                argThat(msg -> msg.contains("高数") && msg.contains("A101")
                        && msg.contains("08:00-09:40") && msg.contains("第2周")),
                eq(5), isNull());
    }
}
