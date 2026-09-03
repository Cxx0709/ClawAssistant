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
 * 先 CONTINUE 计数（inactivityCount+1），连续低置信度达到阈值后才 DEACTIVATE，
 * 避免「估价后追问校区/车型」这类多轮澄清被误判为无关而中断，同时防止 skill 长期占用。
 */
class SkillRouterSessionReleaseTest {

    private SkillDefinition transportSkill() {
        SkillDefinition transport = mock(SkillDefinition.class);
        when(transport.name()).thenReturn("transport");
        when(transport.triggerPolicyName()).thenReturn("transportTriggerPolicy");
        when(transport.priority()).thenReturn(4);
        return transport;
    }

    private SkillDefinition scoutSkill() {
        SkillDefinition scout = mock(SkillDefinition.class);
        when(scout.name()).thenReturn("information-scout");
        when(scout.triggerPolicyName()).thenReturn("scoutTriggerPolicy");
        when(scout.priority()).thenReturn(3);
        return scout;
    }

    /** transport 触发策略：对任何消息都不匹配（模拟「与当前消息无关」） */
    private SkillTriggerPolicy alwaysNoMatchPolicy() {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenReturn(SkillTriggerMatch.noMatch());
        return policy;
    }

    /** information-scout 触发策略：仅当消息含「搜集/收集/搜索」等发现词时命中 */
    private SkillTriggerPolicy scoutPolicy() {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenAnswer(inv -> {
            String msg = inv.getArgument(0);
            return msg != null && msg.contains("搜集")
                    ? new SkillTriggerMatch(true, 0.9, "scout explicit request", false)
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

        // 第一条低置信度消息：CONTINUE 保留 transport（计数 inactivityCount+1），
        // 避免「估价后追问校区/车型」这类多轮澄清被误判为无关而中断。
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, result.action(),
                "第一条低置信度消息不应立刻释放 transport");
        assertEquals("transport", result.primarySkill(),
                "低置信度计数期间应继续保留 transport");
    }

    @Test
    void scoutRequestWinsOverStuckTransportContinuation() {
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition transport = transportSkill();
        SkillDefinition scout = scoutSkill();
        when(registry.getAll()).thenReturn(List.of(transport, scout));
        when(registry.find("transport")).thenReturn(Optional.of(transport));
        when(registry.find("information-scout")).thenReturn(Optional.of(scout));

        SkillSessionStore store = mock(SkillSessionStore.class);
        SkillSession session = SkillSession.create("owner").withActiveSkill("transport");
        when(store.find("owner")).thenReturn(Optional.of(session));

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = alwaysNoMatchPolicy();
        SkillTriggerPolicy scoutTrigger = scoutPolicy();
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);
        when(policyFactory.getPolicy("scoutTriggerPolicy")).thenReturn(scoutTrigger);

        SkillRouter router = routerWith(registry, store, policyFactory);
        SkillRoutingResult result = router.route("帮我搜集一些关于AI的新闻", "owner");

        // 新触发词在 Layer 3 命中信息猎手（先于延续检查），transport 被切换释放
        assertEquals("information-scout", result.primarySkill(),
                "明确的搜索意图应路由到信息猎手，而非卡在 transport");
        assertEquals(SkillRoutingResult.SkillRoutingAction.ACTIVATE, result.action());

        // 切换后上下文应被清理（SkillSession.withActiveSkill 在技能切换时清空 context）
        SkillSession switched = session.withActiveSkill("information-scout");
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

        // 第一条低置信度消息：CONTINUE 保留 transport（计数），
        // 连续达到 LOW_CONFIDENCE_RELEASE_LIMIT 后才 DEACTIVATE 释放。
        assertEquals(SkillRoutingResult.SkillRoutingAction.CONTINUE, result.action(),
                "「你好」第一条应 CONTINUE 计数，而非立刻释放 transport");
        assertEquals("transport", result.primarySkill(),
                "计数期间应继续保留 transport");
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

        // 连续 3 次无关请求（每次按 AgentExecutor.updateSession 语义更新会话）
        for (int i = 0; i < 3; i++) {
            SkillRoutingResult result = router.route("无关请求" + i, "owner");
            session = applyRouting(result, session);
            when(store.find("owner")).thenReturn(Optional.of(session));
        }

        // activeSkill 最终归位 neutral（common），不再被 transport 长期占用
        assertEquals("common", session.activeSkill(),
                "连续无关请求后 activeSkill 不应长期停留在 transport");
        assertEquals(0, session.inactivityCount());
    }
}