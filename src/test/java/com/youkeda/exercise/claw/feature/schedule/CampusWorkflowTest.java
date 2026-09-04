package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CampusWorkflowTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CourseRepository repository = mock(CourseRepository.class);
    private final SemesterService semesters = mock(SemesterService.class);
    private final SemesterConfig config = new SemesterConfig();
    private final CourseService service = new CourseService(repository, new CourseParser(mapper), config, semesters);
    private final CourseImportStateManager state = new CourseImportStateManager();

    private CourseImportFlowActions imports(CourseService courses) {
        return new CourseImportFlowActions(courses, repository, config, state,
                mock(SemesterDetector.class), mock(SemesterRepository.class), semesters,
                mock(CourseMessageFormatter.class), mapper);
    }

    private CourseEntity course(long id, int day, String weeks) {
        CourseEntity c = new CourseEntity("u", "高数", "", day, 1, 2, "A101", 1, 16, weeks);
        c.setId(id);
        c.setSemesterId(10L);
        return c;
    }

    @Test void startAndCancelNeverRemoveStoredCourses() {
        CourseService courses = mock(CourseService.class);
        when(courses.getCourseCount("u")).thenReturn(3);
        CourseImportFlowActions actions = imports(courses);
        assertTrue(actions.handleStartImport("u").contains("existing_count"));
        actions.handleCancel("u");
        verify(courses, never()).deleteAll(anyString());
        verifyNoInteractions(repository);
        assertEquals(CourseImportStateManager.Phase.NONE, state.getPhase("u"));
    }

    @Test void previewPreservesSameNameSlotsAndCancelDoesNotSave() throws Exception {
        CourseImportFlowActions actions = imports(service);
        var args = mapper.readTree("""
                {"courses":[
                  {"course_name":"高数","day_of_week":1,"start_period":1,"end_period":2,"week_type":"ODD"},
                  {"course_name":"高数","day_of_week":1,"start_period":1,"end_period":2,"week_type":"EVEN"}]}
                """);
        var preview = mapper.readTree(actions.handleParse(args, "u"));
        assertEquals("preview", preview.path("status").asText());
        assertEquals(2, preview.path("courses").size());
        assertTrue(preview.path("internal_conflicts").isEmpty());
        actions.handleCancel("u");
        assertEquals("error", mapper.readTree(actions.handleConfirm("u")).path("status").asText());
        verify(repository, never()).replaceAllNullSemester(anyString(), anyList());
        verify(repository, never()).replaceAllBySemester(anyString(), anyLong(), anyList());
    }

    @Test void incompleteCorrectionInvalidatesPriorPreview() throws Exception {
        state.setPendingCourses("u", List.of(course(1, 1, "ALL")));
        state.setWaitingConfirm("u", null);
        var args = mapper.readTree("""
                {"courses":[{"course_name":"高数","day_of_week":1,"start_period":1,"end_period":2},
                            {"course_name":"未识别的课程"}]}
                """);
        var actions = imports(service);
        assertEquals("needs_review", mapper.readTree(actions.handleParse(args, "u")).path("status").asText());
        assertTrue(state.getPendingCourses("u").isEmpty());
        assertEquals("error", mapper.readTree(actions.handleConfirm("u")).path("status").asText());
        verify(repository, never()).replaceAllNullSemester(anyString(), anyList());
    }

    @Test void dateQueryChangesWeekAtMondayAndFiltersParity() {
        SemesterEntity semester = new SemesterEntity();
        semester.setId(10L);
        semester.setStartDate(LocalDate.of(2026, 9, 7));
        when(semesters.getSemesterForDate(eq("u"), any())).thenReturn(Optional.of(semester));
        CourseEntity sunday = course(1, 7, "ODD");
        CourseEntity mondayOdd = course(2, 1, "ODD");
        CourseEntity mondayEven = course(3, 1, "EVEN");
        when(repository.findByUserIdAndSemester("u", 10L)).thenReturn(List.of(sunday, mondayOdd, mondayEven));
        var first = service.getCoursesOnDate("u", LocalDate.of(2026, 9, 13));
        var second = service.getCoursesOnDate("u", LocalDate.of(2026, 9, 14));
        assertEquals(1, first.week());
        assertEquals(List.of(sunday), first.courses());
        assertEquals(2, second.week());
        assertEquals(List.of(mondayEven), second.courses());
        verify(repository, never()).findByUserId(anyString());
    }

    @Test void unknownCalendarIsNotReportedAsFreeTime() {
        var result = service.getCoursesOnDate("u", LocalDate.of(2026, 9, 14));
        assertFalse(result.calendarConfigured());
        assertEquals(-1, result.week());
    }

    @Test void dateLookupIgnoresFutureSemester() {
        SemesterRepository repo = mock(SemesterRepository.class);
        SemesterEntity fall = new SemesterEntity();
        fall.setStartDate(LocalDate.of(2026, 9, 7));
        SemesterEntity spring = new SemesterEntity();
        spring.setStartDate(LocalDate.of(2027, 3, 1));
        when(repo.findByUserId("u")).thenReturn(List.of(spring, fall));
        assertSame(fall, new SemesterService(repo).getSemesterForDate("u", LocalDate.of(2026, 9, 14)).orElseThrow());
        assertTrue(new SemesterService(repo).getSemesterForDate("u", LocalDate.of(2026, 8, 31)).isEmpty());
    }

    @Test void invalidUpdateDoesNotMutateOriginalAndValidUpdateHasBeforeAfter() throws Exception {
        CourseService courses = mock(CourseService.class);
        ScheduleReminderService reminders = mock(ScheduleReminderService.class);
        var actions = new CourseQueryActions(courses, repository, config, semesters,
                mock(CourseMessageFormatter.class), mapper, state, reminders);
        CourseEntity original = course(1, 1, "ALL");
        when(courses.findCourseById(1L)).thenReturn(original);
        var invalid = mapper.readTree("{\"course_id\":1,\"courses\":[{\"start_period\":9}]}");
        assertEquals("error", mapper.readTree(actions.handleUpdate(invalid, "u")).path("status").asText());
        assertEquals(1, original.getStartPeriod());
        verify(courses, never()).updateCourse(any());
        when(courses.getAllCourses("u")).thenReturn(List.of(original, course(2, 3, "ALL")));
        when(courses.updateCourse(any())).thenReturn(true);
        when(reminders.getStatus("u")).thenReturn(Map.of("status", "missing_school"));
        var valid = mapper.readTree("{\"course_id\":1,\"courses\":[{\"day_of_week\":2}]}");
        var result = mapper.readTree(actions.handleUpdate(valid, "u"));
        assertEquals("success", result.path("status").asText());
        assertEquals(1, result.path("before").path("day_of_week").asInt());
        assertEquals(2, result.path("after").path("day_of_week").asInt());
        assertEquals("missing_school", result.path("reminder").path("status").asText());
        verify(courses).updateCourse(argThat(c -> c.getId() == 1 && c.getSemesterId() == 10 && c.getDayOfWeek() == 2));
    }
}
