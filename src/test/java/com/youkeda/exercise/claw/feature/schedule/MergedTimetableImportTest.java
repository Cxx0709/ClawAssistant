package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Manually transcribed screenshot fixture: validates import semantics, not live OCR accuracy. */
class MergedTimetableImportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CourseParser parser = new CourseParser(mapper);
    private final CourseRepository repository = mock(CourseRepository.class);
    private final SemesterService semesters = mock(SemesterService.class);
    private final SemesterConfig config = new SemesterConfig();
    private final CourseService service = new CourseService(repository, parser, config, semesters);
    private final CourseImportStateManager state = new CourseImportStateManager();
    private final CourseImportFlowActions actions = new CourseImportFlowActions(service, repository, config, state,
            mock(SemesterDetector.class), mock(SemesterRepository.class), semesters,
            mock(CourseMessageFormatter.class), mapper);

    private JsonNode fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/schedule/merged-timetable.json")) {
            assertNotNull(stream);
            return mapper.readTree(stream);
        }
    }

    @Test void previewRetainsThirteenRecordsAndDoesNotTreatSplitWeeksAsConflicts() throws Exception {
        var result = mapper.readTree(actions.handleParse(fixture(), "u"));
        assertEquals("preview", result.path("status").asText());
        assertEquals(13, result.path("count").asInt());
        assertTrue(result.path("internal_conflicts").isEmpty());
        var monday = state.getPendingCourses("u").stream()
                .filter(c -> c.getDayOfWeek() == 1 && c.getStartPeriod() == 5).toList();
        assertEquals(2, monday.size());
        assertTrue(monday.stream().allMatch(c -> c.getEndPeriod() == 6));
        assertTrue(result.path("preview_table").asText().contains("形势与政策(五)"));
        verify(repository, never()).replaceAllNullSemester(anyString(), anyList());
        when(repository.replaceAllNullSemester(eq("u"), anyList())).thenAnswer(call -> call.getArgument(1));
        actions.handleConfirm("u");
        verify(repository).replaceAllNullSemester(eq("u"), argThat(rows -> rows.size() == 13));
    }

    @Test void eighthAndNinthWeeksSwitchCoursesInsideSameWednesdayCell() throws Exception {
        SemesterEntity semester = new SemesterEntity("u", 2026, "FALL", LocalDate.of(2026, 9, 7), "TEST");
        semester.setId(1L);
        when(semesters.getSemesterForDate(eq("u"), any())).thenReturn(Optional.of(semester));
        var rows = service.parseOnly("u", fixture().path("courses").toString());
        when(repository.findByUserIdAndSemester("u", 1L)).thenReturn(rows);
        var week8 = service.getCoursesOnDate("u", LocalDate.of(2026, 10, 28)).courses();
        var week9 = service.getCoursesOnDate("u", LocalDate.of(2026, 11, 4)).courses();
        assertEquals(List.of("计算机网络"), week8.stream().filter(c -> c.getStartPeriod() == 5).map(CourseEntity::getCourseName).toList());
        assertEquals(List.of("非结构化数据处理技术与应用"), week9.stream().filter(c -> c.getStartPeriod() == 5).map(CourseEntity::getCourseName).toList());
        assertEquals(2, week8.size());
        assertEquals(3, week9.size());
    }

    @Test void missingImageWeekOrPeriodCannotSilentlyUseDefaults() throws Exception {
        for (String field : List.of("start_period", "end_period", "start_week", "end_week")) {
            var input = fixture();
            ((ObjectNode) input.path("courses").get(0)).remove(field);
            assertEquals("needs_review", mapper.readTree(actions.handleParse(input, "u")).path("status").asText(), field);
            assertEquals(CourseImportStateManager.Phase.NONE, state.getPhase("u"));
        }
        verifyNoInteractions(repository);
    }

    @Test void recognitionProblemInvalidatesEarlierPreview() throws Exception {
        actions.handleParse(fixture(), "u");
        ObjectNode input = (ObjectNode) fixture();
        input.put("recognition_issues", "周一第5–6节的单元格下边框不清晰");
        assertEquals("needs_review", mapper.readTree(actions.handleParse(input, "u")).path("status").asText());
        assertEquals("error", mapper.readTree(actions.handleConfirm("u")).path("status").asText());
        verify(repository, never()).replaceAllNullSemester(anyString(), anyList());
    }

    @Test void backwardsImageRangesMustBeReviewedInsteadOfNormalized() throws Exception {
        var input = fixture();
        ((ObjectNode) input.path("courses").get(0)).put("start_period", 5).put("end_period", 2);
        assertEquals("needs_review", mapper.readTree(actions.handleParse(input, "u")).path("status").asText());
    }

    @Test void correctionCanAddSameNameInNonOverlappingWeeks() {
        var first = new CourseEntity("u", "高数", "", 1, 5, 6, "A101", 1, 8, "ALL");
        var corrected = parser.applyVisionCorrections(List.of(first), """
                {"additions":[{"course_name":"高数","day_of_week":1,"start_period":5,"end_period":6,
                "start_week":9,"end_week":16,"week_type":"ALL","classroom":"B201"}]}
                """);
        assertNotNull(corrected);
        assertEquals(2, corrected.size());
        assertEquals("B201", corrected.get(1).getClassroom());
    }
}
