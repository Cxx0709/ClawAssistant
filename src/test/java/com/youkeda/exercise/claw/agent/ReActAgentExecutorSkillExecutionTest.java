package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.skill.InformationScoutSkillExecutor;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillExecutionConfig;
import com.youkeda.exercise.claw.skill.SkillExecutionMode;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.agent.skill.*;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutor;
import com.youkeda.exercise.claw.agent.runtime.ExecutionLoop;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuardRegistry;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ReActAgentExecutorSkillExecutionTest {

    @Test
    void backgroundSkillShortCircuitsLlmToolLoop() {
        LLMClient llmClient = mock(LLMClient.class);
        SkillRouter skillRouter = mock(SkillRouter.class);
        SkillSessionStore sessionStore = mock(SkillSessionStore.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        SkillExecutionDispatcher dispatcher = mock(SkillExecutionDispatcher.class);
        AgentActivityRecorder activityRecorder = mock(AgentActivityRecorder.class);
        when(activityRecorder.beginRequest()).thenReturn("request-1");
        when(skillRouter.route("最近有什么值得关注", "owner"))
                .thenReturn(SkillRoutingResult.of(
                        "information-scout", Set.of(),
                        SkillRoutingResult.SkillRoutingAction.ACTIVATE,
                        0.95, "explicit request"));
        when(sessionStore.find("owner")).thenReturn(Optional.empty());
        SkillDefinition skill = new SkillDefinition(
                "information-scout", "test", 3, Set.of(), Set.of(), Set.of(),
                null, "scoutTriggerPolicy", null,
                new SkillExecutionConfig(
                        SkillExecutionMode.BACKGROUND_WORKFLOW,
                        InformationScoutSkillExecutor.NAME),
                true);
        when(skillRegistry.find("information-scout")).thenReturn(Optional.of(skill));
        when(dispatcher.dispatch(eq(skill), eq("最近有什么值得关注"), any()))
                .thenAnswer(invocation -> SkillExecutionResult.handledSilent(
                        invocation.getArgument(2)));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PlanStore planStore = mock(PlanStore.class);
        ToolExecutor toolExecutor = new ToolExecutor(
                toolRegistry, mock(SafetyPolicy.class), mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), mock(ToolResultStatusParser.class),
                planStore, objectMapper);
        ExecutionLoop executionLoop = new ExecutionLoop(
                llmClient, toolExecutor, planStore, mock(PlanValidator.class), objectMapper,
                java.util.List.of(),
                new SkillReplyGuardRegistry(java.util.List.of()));

        ReActAgentExecutor executor = new ReActAgentExecutor(
                llmClient,
                toolRegistry,
                mock(ContextStore.class),
                objectMapper,
                planStore,
                mock(LongTermMemoryService.class),
                mock(com.youkeda.exercise.claw.agent.memory.ConversationSummaryService.class),
                skillRouter,
                sessionStore,
                skillRegistry,
                new SkillsProperties(),
                new UserExecutionContext(),
                mock(SkillKnowledgeService.class),
                activityRecorder,
                dispatcher,
                executionLoop,
                new CommonCapabilityRegistry(new SkillsProperties()),
                notHandlingPendingCoordinator());

        String result = executor.execute(new AgentContext()
                .setUserId("owner")
                .setMessage("最近有什么值得关注"));

        assertEquals(ReActAgentExecutor.SILENT_REPLY, result);
        verifyNoInteractions(llmClient);
    }

    private static PendingToolCoordinator notHandlingPendingCoordinator() {
        PendingToolCoordinator mock = mock(PendingToolCoordinator.class);
        when(mock.handleUserMessage(anyString(), anyString()))
                .thenReturn(PendingToolCoordinator.Result.notHandled());
        return mock;
    }
}
