package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.feature.schedule.CourseEntity;
import com.youkeda.exercise.claw.feature.schedule.CourseService;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Provides a live view of today's schedule for the web workspace. */
@RestController
@RequestMapping("/api/workspace")
public class TodayScheduleController {
    private final CourseService courses;
    private final AuthenticatedUser users;

    public TodayScheduleController(CourseService courses, AuthenticatedUser users) {
        this.courses = courses;
        this.users = users;
    }

    @GetMapping("/today-courses")
    public TodayScheduleView today(Authentication authentication) {
        String userId = users.require(authentication).id();
        CourseService.DateCourses schedule = courses.getCoursesOnDate(userId, LocalDate.now());
        return new TodayScheduleView(
                schedule.date().toString(),
                schedule.week(),
                schedule.calendarConfigured(),
                schedule.courses().stream().map(CourseView::from).toList()
        );
    }

    public record TodayScheduleView(
            String date,
            int week,
            boolean calendarConfigured,
            List<CourseView> items
    ) {}

    public record CourseView(
            String courseName,
            String period,
            String classroom,
            String teacher
    ) {
        static CourseView from(CourseEntity course) {
            return new CourseView(
                    text(course.getCourseName()),
                    course.getPeriodDisplay() + "节",
                    text(course.getClassroom()),
                    text(course.getTeacher())
            );
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }
}
