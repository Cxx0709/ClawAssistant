package com.youkeda.exercise.claw.feature.schedule;

import com.youkeda.exercise.claw.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 14, 7, 30);

    private CourseEntity prepare() {
        CourseEntity c = new CourseEntity("u", "高数", "", 1, 1, 2, "A101", 1, 16, "ALL");
        c.setId(1L);
        when(repository.findAll()).thenReturn(List.of(c));
        when(repository.findByUserId("u")).thenReturn(List.of(c));
        when(times.hasBoundSchool("u")).thenReturn(true);
        when(times.getStartTime("u", 1)).thenReturn(LocalTime.of(8, 0));
        when(courses.getCoursesOnDate("u", now.toLocalDate())).thenReturn(
                new CourseService.DateCourses(now.toLocalDate(), 2, 10L, true, List.of(c)));
        return c;
    }

    @Test void deduplicatesButRescheduledTimeCanNotifyAgain() {
        CourseEntity c = prepare();
        reminders.checkReminders(now);
        reminders.checkReminders(now);
        verify(sink, times(1)).publish(eq("u"), eq("COURSE_REMINDER"), anyString(), anyString(), eq(5), isNull());
        c.setStartPeriod(3);
        when(times.getStartTime("u", 3)).thenReturn(LocalTime.of(10, 0));
        reminders.checkReminders(now.withHour(9));
        verify(sink, times(2)).publish(eq("u"), eq("COURSE_REMINDER"), anyString(), anyString(), eq(5), isNull());
    }

    @Test void failedDeliveryCanRetryWithinWindow() {
        prepare();
        when(sink.publish(anyString(), anyString(), anyString(), anyString(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("temporary delivery failure")).thenReturn(1L);
        reminders.checkReminders(now);
        reminders.checkReminders(now.plusSeconds(10));
        reminders.checkReminders(now.plusSeconds(20));
        verify(sink, times(2)).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), isNull());
    }

    @Test void missingSchoolDoesNotPretendReminderIsReady() {
        prepare();
        when(times.hasBoundSchool("u")).thenReturn(false);
        reminders.checkReminders(now);
        verifyNoInteractions(sink);
        assertEquals("missing_school", reminders.getStatus("u").get("status"));
    }

    @Test void onlyDateFilteredCoursesAreNotified() {
        prepare();
        when(courses.getCoursesOnDate("u", now.toLocalDate())).thenReturn(
                new CourseService.DateCourses(now.toLocalDate(), 2, 10L, true, List.of()));
        reminders.checkReminders(now);
        verifyNoInteractions(sink);
    }
}
