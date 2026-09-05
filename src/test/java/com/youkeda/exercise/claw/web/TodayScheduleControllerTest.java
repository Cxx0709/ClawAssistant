package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.feature.schedule.CourseEntity;
import com.youkeda.exercise.claw.feature.schedule.CourseService;
import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TodayScheduleController.class)
@Import(SecurityConfig.class)
class TodayScheduleControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CourseService courses;
    @MockBean AuthenticatedUser users;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/workspace/today-courses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice")
    void returnsLatestCoursesForAuthenticatedUser() throws Exception {
        LocalDate today = LocalDate.now();
        when(users.require(any())).thenReturn(new AppUser(
                "tenant-a", "alice", "hash", "Alice", true, Instant.now()));
        CourseEntity course = new CourseEntity(
                "tenant-a", "数据挖掘技术", "方欢", 1, 7, 8, "明理北104", 1, 16, "ALL");
        when(courses.getCoursesOnDate("tenant-a", today)).thenReturn(
                new CourseService.DateCourses(today, 1, 1L, true, List.of(course)));

        mvc.perform(get("/api/workspace/today-courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseName").value("数据挖掘技术"))
                .andExpect(jsonPath("$.items[0].period").value("7-8节"))
                .andExpect(jsonPath("$.items[0].classroom").value("明理北104"));

        verify(courses).getCoursesOnDate("tenant-a", today);
    }
}
