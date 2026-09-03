package com.youkeda.exercise.claw.skill;
import com.youkeda.exercise.claw.skill.InformationScoutSkillExecutor;
import com.youkeda.exercise.claw.skill.SkillExecutionRequest;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;
import com.youkeda.exercise.claw.skill.InformationScoutIntentResolver;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;

import com.youkeda.exercise.claw.feature.scout.ScoutSubmissionResult;
import com.youkeda.exercise.claw.feature.scout.ScoutSubmissionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InformationScoutSkillExecutorTest {

    @Test
    void startsTopicWorkflowSilentlyWithoutLlmToolCall() {
        ScoutSubmissionService submissionService = mock(ScoutSubmissionService.class);
        when(submissionService.submit("Claude Code 最近的更新", "scoutWorkflow"))
                .thenReturn(ScoutSubmissionResult.started("task-1"));
        InformationScoutSkillExecutor executor = new InformationScoutSkillExecutor(
                new InformationScoutIntentResolver(), submissionService);
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("information-scout");

        SkillExecutionResult result = executor.execute(new SkillExecutionRequest(
                null,
                "跟踪 Claude Code 最近的更新",
                session,
                "scoutWorkflow"));

        assertEquals(SkillExecutionResult.Status.REPLY, result.status());
        assertEquals("已创建关注任务，发现重要内容会通知你。", result.message());
        assertFalse(result.session().hasPendingAction(
                SkillPendingCoordinator.START_INFORMATION_SCOUT));
        verify(submissionService).submit(
                "Claude Code 最近的更新", "scoutWorkflow");
    }

    @Test
    void keepsDuplicateSubmissionSilent() {
        ScoutSubmissionService submissionService = mock(ScoutSubmissionService.class);
        when(submissionService.submit("AI Agent 最近动态", "scoutWorkflow"))
                .thenReturn(ScoutSubmissionResult.duplicate());
        InformationScoutSkillExecutor executor = new InformationScoutSkillExecutor(
                new InformationScoutIntentResolver(), submissionService);

        SkillExecutionResult result = executor.execute(new SkillExecutionRequest(
                null, "跟踪 AI Agent 最近动态",
                SkillSession.create("owner"), "scoutWorkflow"));

        assertEquals(SkillExecutionResult.Status.REPLY, result.status());
        assertEquals("已创建关注任务，发现重要内容会通知你。", result.message());
    }

    @Test
    void asksOnceForMissingTopicAndStoresPendingState() {
        ScoutSubmissionService submissionService = mock(ScoutSubmissionService.class);
        InformationScoutSkillExecutor executor = new InformationScoutSkillExecutor(
                new InformationScoutIntentResolver(), submissionService);

        SkillExecutionResult result = executor.execute(new SkillExecutionRequest(
                null, "帮我查一下", SkillSession.create("owner"), "scoutWorkflow"));

        assertEquals(SkillExecutionResult.Status.REPLY, result.status());
        assertEquals("你想重点查找哪个主题？", result.message());
        assertTrue(result.session().hasPendingAction(
                SkillPendingCoordinator.START_INFORMATION_SCOUT));
    }
}
