package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.feature.schedule.ExamEntity;
import com.youkeda.exercise.claw.feature.schedule.ExamService;
import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
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

@WebMvcTest(ExamBoardController.class)
@Import({SecurityConfig.class, UserExecutionContext.class})
class ExamBoardControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ExamService exams;
    @MockBean AuthenticatedUser users;

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/workspace/exams"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice")
    void returnsOnlyAuthenticatedTenantExamsAndTodayHasZeroDaysLeft() throws Exception {
        when(users.require(any())).thenReturn(user("tenant-a", "alice"));
        ExamEntity today = exam(1L, "tenant-a", "高等数学", LocalDate.now(), "14:00");
        when(exams.getUpcomingExams("tenant-a")).thenReturn(List.of(today));

        mvc.perform(get("/api/workspace/exams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseName").value("高等数学"))
                .andExpect(jsonPath("$.items[0].daysLeft").value(0));

        verify(exams).getUpcomingExams("tenant-a");
    }

    @Test
    @WithMockUser(username = "alice")
    void ignoresDirtyAndPastDatesAndSortsByDateThenStartTime() throws Exception {
        when(users.require(any())).thenReturn(user("tenant-a", "alice"));
        ExamEntity later = exam(3L, "tenant-a", "英语", LocalDate.now().plusDays(3), "09:00");
        ExamEntity sameDayLater = exam(2L, "tenant-a", "物理", LocalDate.now().plusDays(1), "14:00");
        ExamEntity nearest = exam(1L, "tenant-a", "数学", LocalDate.now().plusDays(1), "08:00");
        ExamEntity past = exam(4L, "tenant-a", "历史考试", LocalDate.now().minusDays(1), "08:00");
        ExamEntity dirty = exam(5L, "tenant-a", "脏数据", LocalDate.now(), "08:00");
        dirty.setExamDate("not-a-date");
        when(exams.getUpcomingExams("tenant-a"))
                .thenReturn(List.of(later, dirty, sameDayLater, past, nearest));

        mvc.perform(get("/api/workspace/exams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].courseName").value("数学"))
                .andExpect(jsonPath("$.items[1].courseName").value("物理"))
                .andExpect(jsonPath("$.items[2].courseName").value("英语"));
    }

    private static AppUser user(String id, String username) {
        return new AppUser(id, username, "hash", username, true, Instant.now());
    }

    private static ExamEntity exam(Long id, String userId, String course, LocalDate date, String startTime) {
        ExamEntity exam = new ExamEntity(userId, course, date.toString(), startTime, "10:00", "A101", "FINAL");
        exam.setId(id);
        return exam;
    }
}
