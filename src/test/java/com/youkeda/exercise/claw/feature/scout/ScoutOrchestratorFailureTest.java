package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.collector.CollectorRegistry;
import com.youkeda.exercise.claw.feature.scout.context.UserBehaviorAnalyzer;
import com.youkeda.exercise.claw.feature.scout.context.UserContextService;
import com.youkeda.exercise.claw.feature.scout.judge.DecisionMaker;
import com.youkeda.exercise.claw.feature.scout.matcher.CandidateMatcher;
import com.youkeda.exercise.claw.feature.scout.notifier.NotificationService;
import com.youkeda.exercise.claw.feature.scout.planner.SearchPlanner;
import com.youkeda.exercise.claw.feature.scout.processor.InformationProcessor;
import com.youkeda.exercise.claw.feature.scout.store.InformationStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoutOrchestratorFailureTest {

    @Test
    void propagatesExecutionFailureToWorkflowWorker() {
        UserContextService contextService = mock(UserContextService.class);
        when(contextService.buildProfile()).thenThrow(new IllegalStateException("profile failed"));
        ScoutOrchestrator orchestrator = new ScoutOrchestrator(
                contextService,
                mock(UserBehaviorAnalyzer.class),
                mock(SearchPlanner.class),
                mock(CollectorRegistry.class),
                mock(InformationProcessor.class),
                mock(InformationStore.class),
                mock(CandidateMatcher.class),
                mock(DecisionMaker.class),
                mock(NotificationService.class),
                new ScoutProperties());

        assertThrows(IllegalStateException.class, () -> orchestrator.run("AI Agent"));
    }
}
