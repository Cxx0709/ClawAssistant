package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skill 会话卡死修复测试：验证 handleContinuation 的低置信度释放机制。
 *
 * <p>场景：用户进入某个 Skill（如 transport）后，后续消息与旧 skill 弱关联时
 * 本轮走 common，以 NONE 保留可恢复的旧任务；连续无关消息达到阈值后再释放旧状态。
 */
class SkillRouterSessionReleaseTest {

    private SkillDefinition transportSkill() {
        SkillDefinition transport = mock(SkillDefinition.class);
        when(transport.name()).thenReturn("transport");
        when(transport.triggerPolicyName()).thenReturn("transportTriggerPolicy");
        when(transport.priority()).thenReturn(4);
        return transport;
    }

    private SkillDefinition researchSkill() {
        SkillDefinition research = mock(SkillDefinition.class);
        when(research.name()).thenReturn("research");
        when(research.triggerPolicyName()).thenReturn("researchTriggerPolicy");
        when(research.priority()).thenReturn(3);
        return research;
    }

    /** transport 触发策略：对任何消息都不匹配（模拟「与当前消息无关」） */
    private SkillTriggerPolicy alwaysNoMatchPolicy() {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenReturn(SkillTriggerMatch.noMatch());
        return policy;
    }

    /** research 触发策略：仅当消息含「搜集/收集/搜索」等发现词时命中 */
    private SkillTriggerPolicy researchPolicy() {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenAnswer(inv -> {
            String msg = inv.getArgument(0);
            return msg != null && msg.contains("搜集")
                    ? new SkillTriggerMatch(true, 0.9, "research explicit request", false)
                    : SkillTriggerMatch.noMatch();
        });
        return policy;
    }

    private SkillRouter routerWith(SkillRegistry registry,
                                   SkillSessionStore store,
                                   TriggerPolicyFactory policyFactory) {
        return new SkillRouter(registry, store, policyFactory,
                mock(SkillLlmRouter.class), emptyTriggers());
    }

    private TriggerProperties emptyTriggers() {
        TriggerProperties props = mock(TriggerProperties.class);
        when(props.getTriggers()).thenReturn(Map.of());
        return props;
    }

    /**
     * 模拟 AgentExecutor.updateSession 的路由动作 → 会话变化。
     * 仅用于测试中串联多次 route() 调用（AgentExecutor 里的逻辑不变）。
     */
    private SkillSession applyRouting(SkillRoutingResult result, SkillSession session) {
        switch (result.action()) {
            case DEACTIVATE -> {
                return SkillSession.create(session.userId());
            }
            case ACTIVATE, SWITCH -> {
                return session.withActiveSkill(result.primarySkill());
            }
            case CONTINUE -> {
                if (result.confidence() >= 0.3) {
                    return session.withResetInactivity();
                }
                return session.withIncrementInactivity();
            }
            case NONE -> {
                if (!"common".equals(session.activeSkill())) {
                    return session.withIncrementInactivity();
                }
                return session;
            }
        }
        return session;
    }

    // ============ Case 1：进入 transport 后，第一条无关消息不应立刻释放 transport ============

    @Test
    void firstUnrelatedMessageKeepsTransportWithCounting() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = transportSkill();
        when(registry.getAll()).thenReturn(List.of(transport));
        when(registry.find("transport")).thenReturn(Optional.of(transport));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = alwaysNoMatchPolicy();
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);

        SkillRouter router = routerWith(registry, store, policyFactory);
        SkillRoutingResult result = router.route("帮我写一个AI新闻总结", "owner");

        assertEquals(SkillRoutingResult.SkillRoutingAction.NONE, result.action(),
                "第一条低置信度消息不应立刻释放 transport");
        assertEquals("common", result.primarySkill(), "保留状态不应让本轮继续使用 transport");
    }

    @Test
    void researchRequestWinsOverStuckTransportContinuation() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = transportSkill();
        SkillDefinition research = researchSkill();
        when(registry.getAll()).thenReturn(List.of(transport, research));
        when(registry.find("transport")).thenReturn(Optional.of(transport));
        when(registry.find("research")).thenReturn(Optional.of(research));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = alwaysNoMatchPolicy();
        SkillTriggerPolicy researchTrigger = researchPolicy();
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);
        when(policyFactory.getPolicy("researchTriggerPolicy")).thenReturn(researchTrigger);

        SkillRouter router = routerWith(registry, store, policyFactory);
        SkillRoutingResult result = router.route("帮我搜集一些关于AI的新闻", "owner");

        // 新触发词在 Layer 3 命中信息研究（先于延续检查），transport 被切换释放
        assertEquals("research", result.primarySkill(),
                "明确的搜索意图应路由到信息研究，而非卡在 transport");
        assertEquals(SkillRoutingResult.SkillRoutingAction.ACTIVATE, result.action());

        // 切换后上下文应被清理（SkillSession.withActiveSkill 在技能切换时清空 context）
        SkillSession switched = session.withActiveSkill("research");
        assertEquals(Map.of(), switched.context(), "技能切换后应清理旧技能上下文");
    }

    // ============ Case 2：进入 transport 后，闲聊「你好」不应立刻延续 transport，但需计数达阈值才释放 ============

    @Test
    void casualGreetingDoesNotImmediatelyContinueStuckSkill() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = transportSkill();
        when(registry.getAll()).thenReturn(List.of(transport));
        when(registry.find("transport")).thenReturn(Optional.of(transport));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = alwaysNoMatchPolicy();
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);

        SkillRouter router = routerWith(registry, store, policyFactory);
        SkillRoutingResult result = router.route("你好", "owner");

        assertEquals(SkillRoutingResult.SkillRoutingAction.NONE, result.action());
        assertEquals("common", result.primarySkill(), "闲聊应立即由通用能力处理");
    }


    // ============ Case 3：连续无关请求，activeSkill 最终应回到 neutral ============

    @Test
    void consecutiveIrrelevantRequestsEndInNeutralCommonSession() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = transportSkill();
        when(registry.getAll()).thenReturn(List.of(transport));
        when(registry.find("transport")).thenReturn(Optional.of(transport));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = alwaysNoMatchPolicy();
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);

        SkillRouter router = routerWith(registry, store, policyFactory);

        // 五轮保留窗口结束后释放旧状态，每轮都使用 common。
        for (int i = 0; i < 6; i++) {
            SkillRoutingResult result = router.route("无关请求" + i, "owner");
            assertEquals("common", result.primarySkill());
            session = applyRouting(result, session);
            when(store.find("owner")).thenReturn(Optional.of(session));
        }

        // activeSkill 最终归位 neutral（common），不再被 transport 长期占用
        assertEquals("common", session.activeSkill(),
                "连续无关请求后 activeSkill 不应长期停留在 transport");
        assertEquals(0, session.inactivityCount());
    }
}
