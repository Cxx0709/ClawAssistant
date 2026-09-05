package com.youkeda.exercise.claw.agent.skill;
import com.youkeda.exercise.claw.agent.runtime.SkillExecutor;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;
import com.youkeda.exercise.claw.skill.SkillExecutionMode;
import com.youkeda.exercise.claw.skill.SkillExecutionConfig;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.skill.SkillDefinition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillExecutionDispatcherTest {

    @Test
    void dispatchesBackgroundSkillToConfiguredExecutorAndWorkflow() {
        SkillExecutor executor = mock(SkillExecutor.class);
        when(executor.getName()).thenReturn("testExecutor");
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("research");
        SkillExecutionResult expected = SkillExecutionResult.handledSilent(session);
        when(executor.execute(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        SkillsProperties properties = new SkillsProperties();
        properties.setSkillWorkflowBindings(Map.of("research", "researchWorkflow"));
        SkillExecutionDispatcher dispatcher = new SkillExecutionDispatcher(
                List.of(executor), properties);
        SkillDefinition skill = new SkillDefinition(
                "research", "test", 3, Set.of(), Set.of(), Set.of(),
                "prompt.txt", "researchTriggerPolicy", null,
                new SkillExecutionConfig(
                        SkillExecutionMode.BACKGROUND_WORKFLOW, "testExecutor"),
                true);

        SkillExecutionResult result = dispatcher.dispatch(
                skill, "最近有什么值得关注", session);

        assertEquals(SkillExecutionResult.Status.HANDLED_SILENT, result.status());
        verify(executor).execute(argThat(request ->
                "researchWorkflow".equals(request.workflowName())
                        && request.skill() == skill));
    }
}
