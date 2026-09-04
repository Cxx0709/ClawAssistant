package com.youkeda.exercise.claw.tool.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExamImportToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExamService service = mock(ExamService.class);
    private final ExamImportTool tool = new ExamImportTool(mapper, mock(ToolRegistry.class), service, mock(ExamRepository.class));
    private final ToolExecutionContext context = new ToolExecutionContext("考试地点明理北104", null, "u");
    private static final String EXAM = """
            {"course_name":"数据挖掘技术","exam_date":"2026-12-20","start_time":"09:00","end_time":"11:00","location":"明理北104"}
            """;

    private JsonNode run(String args) throws Exception { return mapper.readTree(tool.execute(args, context)); }

    @Test void schemaDeclaresExamObjectsWithRequiredNameAndDate() {
        var items = tool.getParameters().path("properties").path("courses").path("items");
        assertEquals("object", items.path("type").asText());
        assertTrue(items.path("properties").has("location"));
        assertTrue(items.path("required").toString().contains("course_name"));
        assertTrue(items.path("required").toString().contains("exam_date"));
    }

    @Test void importsObjectArrayAndPreservesChineseLocation() throws Exception {
        when(service.saveExams(eq("u"), anyList())).thenAnswer(call -> call.getArgument(1));
        var result = run("{\"action\":\"import\",\"courses\":[" + EXAM + "]}");
        assertEquals(1, result.path("count").asInt());
        assertEquals("明理北104", result.path("exams").get(0).path("location").asText());
    }

    @Test void acceptsLegacyJsonStringRecords() throws Exception {
        when(service.saveExams(eq("u"), anyList())).thenAnswer(call -> call.getArgument(1));
        var result = run("{\"action\":\"import\",\"courses\":[" + mapper.writeValueAsString(EXAM) + "]}");
        assertEquals(1, result.path("count").asInt());
    }

    @Test void naturalLanguageAndLocationOnlyGiveActionableErrors() throws Exception {
        for (String row : List.of("\"考试地点明理北104\"", "{\"location\":\"明理北104\"}")) {
            var result = run("{\"action\":\"import\",\"courses\":[" + row + "]}");
            assertEquals("needs_input", result.path("status").asText());
            assertTrue(result.path("error").asText().contains("course_name"));
        }
        verifyNoInteractions(service);
    }

    @Test void rejectsWholeBatchIfOneRecordIsIncomplete() throws Exception {
        var result = run("{\"action\":\"import\",\"courses\":[" + EXAM + ",{\"location\":\"B101\"}]}");
        assertEquals("needs_input", result.path("status").asText());
        assertTrue(result.path("error").asText().contains("第2条"));
        verifyNoInteractions(service);
    }

    @Test void rejectsInvalidCalendarDateWithoutSaving() throws Exception {
        var result = run("{\"action\":\"import\",\"courses\":[" + EXAM.replace("2026-12-20", "2026-02-30") + "]}");
        assertEquals("needs_input", result.path("status").asText());
        verifyNoInteractions(service);
    }

    private ExamEntity existing() {
        var exam = new ExamEntity("u", "数据挖掘技术", "2026-12-20", "09:00", "11:00", "A101", "FINAL");
        exam.setId(3L);
        exam.setSeatNumber("28");
        exam.setNotes("闭卷");
        return exam;
    }

    @Test void locationOnlyUpdatePreservesOtherFieldsAndDoesNotImport() throws Exception {
        var exam = existing();
        when(service.findExamById(3L)).thenReturn(exam);
        when(service.updateExam(any())).thenReturn(true);
        var result = run("{\"action\":\"update\",\"exam_id\":3,\"location\":\"明理北104\"}");
        assertTrue(result.path("success").asBoolean());
        verify(service).updateExam(argThat(e -> e.getId() == 3L && "明理北104".equals(e.getLocation())
                && "2026-12-20".equals(e.getExamDate()) && "09:00".equals(e.getStartTime())
                && "11:00".equals(e.getEndTime()) && "28".equals(e.getSeatNumber()) && "闭卷".equals(e.getNotes())));
        verify(service, never()).saveExams(anyString(), anyList());
        assertEquals("A101", exam.getLocation());
    }

    @Test void unknownIdAsksToQueryRatherThanGuessingExam() throws Exception {
        var result = run("{\"action\":\"update\",\"location\":\"明理北104\"}");
        assertTrue(result.path("error").asText().contains("query_all"));
        verifyNoInteractions(service);
    }

    @Test void rejectsUpdatesToOtherUsersExam() throws Exception {
        var exam = existing();
        exam.setUserId("other");
        when(service.findExamById(3L)).thenReturn(exam);
        assertFalse(run("{\"action\":\"update\",\"exam_id\":3,\"location\":\"明理北104\"}").path("success").asBoolean());
        verify(service, never()).updateExam(any());
    }

    @Test void invalidUpdateDoesNotMutateStoredObject() throws Exception {
        var exam = existing();
        when(service.findExamById(3L)).thenReturn(exam);
        assertEquals("needs_input", run("{\"action\":\"update\",\"exam_id\":3,\"new_exam_date\":\"2026-02-30\"}").path("status").asText());
        assertEquals("2026-12-20", exam.getExamDate());
        verify(service, never()).updateExam(any());
    }

    @Test void nullArgumentsReturnStructuredError() throws Exception {
        assertEquals("needs_input", run("null").path("status").asText());
        verifyNoInteractions(service);
    }
}
