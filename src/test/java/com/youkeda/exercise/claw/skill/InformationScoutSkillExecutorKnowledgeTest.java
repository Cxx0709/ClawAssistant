package com.youkeda.exercise.claw.skill;

import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.feature.scout.ScoutExecutionContext;
import com.youkeda.exercise.claw.feature.scout.ScoutKnowledgeProvider;
import com.youkeda.exercise.claw.feature.scout.ScoutSubmissionResult;
import com.youkeda.exercise.claw.feature.scout.ScoutSubmissionService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InformationScoutSkillExecutorKnowledgeTest {

    @Test
    void recallsKnowledgeBeforeSubmittingStructuredContext() {
        ScoutKnowledgeProvider provider = mock(ScoutKnowledgeProvider.class);
        ScoutSubmissionService submissionService = mock(ScoutSubmissionService.class);
        ScoutExecutionContext context = new ScoutExecutionContext(
                "AI Agent 最近动态", "planning", "decision");
        when(provider.forExplicitQuery("AI Agent 最近动态")).thenReturn(context);
        when(submissionService.submit(context, "scoutWorkflow"))
                .thenReturn(ScoutSubmissionResult.started("task"));
        InformationScoutSkillExecutor executor = new InformationScoutSkillExecutor(
                new InformationScoutIntentResolver(), submissionService, provider);

        executor.execute(new SkillExecutionRequest(
                null, "跟踪 AI Agent 最近动态", SkillSession.create("owner"), "scoutWorkflow"));

        verify(provider).forExplicitQuery("AI Agent 最近动态");
        verify(submissionService).submit(context, "scoutWorkflow");
    }
}
