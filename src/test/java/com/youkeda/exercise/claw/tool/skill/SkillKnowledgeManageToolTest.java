package com.youkeda.exercise.claw.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.ai.retrieval.KnowledgeImportResult;
import com.youkeda.exercise.claw.ai.retrieval.KnowledgeStoreStatus;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeImportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillKnowledgeManageToolTest {

    @Test
    void onlyAvailableWhenFeatureFlagAndExplicitIntentAreBothPresent() {
        SkillKnowledgeManageTool tool = new SkillKnowledgeManageTool(
                mock(ToolRegistry.class), mock(SkillKnowledgeImportService.class),
                new ObjectMapper());

        ReflectionTestUtils.setField(tool, "managementEnabled", false);
        ReflectionTestUtils.setField(tool, "allowedUsers", "admin");
        assertFalse(tool.isAvailable(new ToolExecutionContext(
                "请导入技能知识库", null, "admin")));

        ReflectionTestUtils.setField(tool, "managementEnabled", true);
        assertFalse(tool.isAvailable(new ToolExecutionContext(
                "请把这份文档导入技能知识库", null, "ordinary-user")));
        assertFalse(tool.isAvailable(new ToolExecutionContext(
                "帮我规划一次旅行", null, "admin")));
        assertTrue(tool.isAvailable(new ToolExecutionContext(
                "请把这份文档导入技能知识库", null, "admin")));
        assertTrue(tool.isAvailable(new ToolExecutionContext(
                "查看 Skill 知识库状态", null, "admin")));
    }

    @Test
    void successfulActionsUseCanonicalToolSuccessStatus() {
        ObjectMapper mapper = new ObjectMapper();
        SkillKnowledgeImportService service = mock(SkillKnowledgeImportService.class);
        when(service.importDocument(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new KnowledgeImportResult("imported", "doc-1", 1, 1, ""));
        when(service.status("travel"))
                .thenReturn(new KnowledgeStoreStatus(true, "skill_knowledge", 1, "OK"));

        SkillKnowledgeManageTool tool = new SkillKnowledgeManageTool(
                mock(ToolRegistry.class), service, mapper);
        ReflectionTestUtils.setField(tool, "managementEnabled", true);
        ReflectionTestUtils.setField(tool, "allowedUsers", "admin");
        ToolExecutionContext context = new ToolExecutionContext(
                "请导入技能知识库", null, "admin");
        ToolResultStatusParser parser = new ToolResultStatusParser(mapper);

        String imported = tool.execute(
                "{\"action\":\"import\",\"skillName\":\"travel\",\"content\":\"规则\"}",
                context);
        String status = tool.execute(
                "{\"action\":\"status\",\"skillName\":\"travel\"}",
                new ToolExecutionContext("查看 Skill 知识库状态", null, "admin"));

        assertEquals(ResultStatus.SUCCESS, parser.parse(imported));
        assertEquals(ResultStatus.SUCCESS, parser.parse(status));
    }

    @Test
    void rejectsAnActionThatDoesNotMatchTheUsersExplicitIntent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillKnowledgeImportService service = mock(SkillKnowledgeImportService.class);
        SkillKnowledgeManageTool tool = new SkillKnowledgeManageTool(
                mock(ToolRegistry.class), service, mapper);
        ReflectionTestUtils.setField(tool, "managementEnabled", true);
        ReflectionTestUtils.setField(tool, "allowedUsers", "admin");

        String result = tool.execute(
                "{\"action\":\"import\",\"skillName\":\"travel\",\"content\":\"规则\"}",
                new ToolExecutionContext("查看 Skill 知识库状态", null, "admin"));

        assertEquals(ResultStatus.FAILED, new ToolResultStatusParser(mapper).parse(result));
        assertEquals("FAILED", mapper.readTree(result).path("status").asText());
        String nullContextResult = tool.execute(
                "{\"action\":\"import\",\"skillName\":\"travel\",\"content\":\"规则\"}", null);
        assertEquals("FAILED", mapper.readTree(nullContextResult).path("status").asText());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void schemaOnlyAdvertisesImplementedActions() {
        SkillKnowledgeManageTool tool = new SkillKnowledgeManageTool(
                mock(ToolRegistry.class), mock(SkillKnowledgeImportService.class),
                new ObjectMapper());

        String schema = tool.getParameters().toString();
        assertTrue(schema.contains("import"));
        assertTrue(schema.contains("soft_delete_document"));
        assertTrue(schema.contains("status"));
        assertTrue(schema.contains("contentType"));
        assertTrue(schema.contains("sourceVersion"));
        assertFalse(schema.contains("\"format\""));
        assertFalse(schema.contains("list_documents"));
        assertFalse(schema.contains("reindex"));
    }
}
