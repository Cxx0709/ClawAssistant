package com.youkeda.exercise.claw.agent.skill;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillRouterPendingActionTest {

    @Test
    void routesShortAnswerToPendingResearchActionBeforeNormalTriggerChecks() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillSessionStore store = mock(SkillSessionStore.class);
        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillLlmRouter llmRouter = mock(SkillLlmRouter.class);
        when(llmRouter.route(anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(SkillRoutingResult.of("research", java.util.Set.of(),
                        SkillRoutingResult.SkillRoutingAction.CONTINUE, 0.95, "pending topic answer"));
        TriggerProperties triggerProperties = mock(TriggerProperties.class);
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("research")
                .withPendingAction("START_RESEARCH", "query");
        when(store.find("owner")).thenReturn(Optional.of(session));
        SkillRouter router = new SkillRouter(
                registry, store, policyFactory, llmRouter, triggerProperties);

        SkillRoutingResult result = router.route("AI / 深度学习", "owner");

        assertEquals("research", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, result.action());
        assertEquals(0.95, result.confidence());
        verify(llmRouter).route(eq("AI / 深度学习"), eq("owner"), eq(registry), eq(Optional.of(session)), anyList());
    }

    @Test
    void cancellationDeactivatesPendingResearchAction() {
        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("research")
                .withPendingAction("START_RESEARCH", "query");
        when(store.find("owner")).thenReturn(Optional.of(session));
        SkillRouter router = new SkillRouter(
                mock(SkillRegistry.class), store, mock(TriggerPolicyFactory.class),
                mock(SkillLlmRouter.class), mock(TriggerProperties.class));

        SkillRoutingResult result = router.route("算了，不查了", "owner");

        assertEquals("common", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.DEACTIVATE, result.action());
    }

    @Test
    void strongCrossSkillTriggerPreemptsConfirmOnlyPending() {
        // 纯确认态 pending（pendingSlot 为空，如行程估价待确认）：强新意图应抢占
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = skill("transport", "transportTriggerPolicy", 4);
        SkillDefinition research = skill("research", "researchTriggerPolicy", 3);
        when(registry.getAll()).thenReturn(List.of(transport, research));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("transport")
                .withPendingAction("RIDE_ESTIMATE_CONFIRM", null);
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = policyMatchingNothing();
        SkillTriggerPolicy researchPolicy = policyMatching("搜集");
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);
        when(policyFactory.getPolicy("researchTriggerPolicy")).thenReturn(researchPolicy);

        SkillLlmRouter llmRouter = mock(SkillLlmRouter.class);
        SkillRouter router = new SkillRouter(
                registry, store, policyFactory, llmRouter, emptyTriggers());

        SkillRoutingResult result = router.route("帮我搜集AI新闻", "owner");

        assertEquals("research", result.primarySkill(),
                "行程估价待确认期间，明确的搜索意图应抢占到信息研究");
        assertEquals(SkillRoutingResult.SkillRoutingAction.ACTIVATE, result.action());
        verifyNoInteractions(llmRouter);
    }

    @Test
    void inputCollectingPendingIsNotPreemptedByTriggerWordAnswer() {
        // 信息研究等待补充主题（pendingSlot="query"）：回答「天气」应是主题，而非触发 weather 技能
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition research = skill("research", "researchTriggerPolicy", 3);
        SkillDefinition weather = skill("weather", "keywordTriggerPolicy", 2);
        when(registry.getAll()).thenReturn(List.of(research, weather));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner")
                .withActiveSkill("research")
                .withPendingAction("START_RESEARCH", "query");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy researchPolicy = policyMatchingNothing();
        when(policyFactory.getPolicy("researchTriggerPolicy")).thenReturn(researchPolicy);

        TriggerProperties triggers = mock(TriggerProperties.class);
        when(triggers.getTriggers()).thenReturn(Map.of("weather", List.of("天气")));

        SkillLlmRouter semanticRouter = mock(SkillLlmRouter.class);
        when(semanticRouter.route(anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(SkillRoutingResult.of("research", java.util.Set.of(),
                        SkillRoutingResult.SkillRoutingAction.CONTINUE, 0.95, "pending topic answer"));
        SkillRouter router = new SkillRouter(
                registry, store, policyFactory, semanticRouter, triggers);

        SkillRoutingResult result = router.route("天气", "owner");

        // 不被抢占：仍按 pending 流程回到 research，等待收集主题
        assertEquals("research", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, result.action());
    }

    private SkillDefinition skill(String name, String triggerPolicyName, int priority) {
        SkillDefinition def = mock(SkillDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.triggerPolicyName()).thenReturn(triggerPolicyName);
        when(def.priority()).thenReturn(priority);
        return def;
    }

    private SkillTriggerPolicy policyMatchingNothing() {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenReturn(SkillTriggerMatch.noMatch());
        return policy;
    }

    private SkillTriggerPolicy policyMatching(String keyword) {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenAnswer(inv -> {
            String msg = inv.getArgument(0);
            return msg != null && msg.contains(keyword)
                    ? new SkillTriggerMatch(true, 0.9, keyword + " match", false)
                    : SkillTriggerMatch.noMatch();
        });
        return policy;
    }

    private TriggerProperties emptyTriggers() {
        TriggerProperties props = mock(TriggerProperties.class);
        when(props.getTriggers()).thenReturn(Map.of());
        return props;
    }
}
