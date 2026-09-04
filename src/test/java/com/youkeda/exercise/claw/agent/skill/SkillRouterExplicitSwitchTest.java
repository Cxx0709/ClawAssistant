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

class SkillRouterExplicitSwitchTest {

    @Test
    void travelDoesNotStealTransportKeywordInExplicitSwitch() {
        // Layer 2 回归：travel 使用默认关键词策略，不得通过共享 KeywordTriggerPolicy
        // 匹配到 transport 的「怎么去/坐车」。剥掉「不要」后剩「坐车了，帮我生成一张水墨风风景图」，
        // 只有 transport（自定义 transportTriggerPolicy）能匹配。
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition travel = skill("travel", null, 5);
        SkillDefinition transport = skill("transport", "transportTriggerPolicy", 4);
        when(registry.getAll()).thenReturn(List.of(travel, transport));

        SkillSessionStore store = mock(SkillSessionStore.class);
        when(store.find("owner")).thenReturn(Optional.empty());

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = policyMatching("坐车");
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);

        TriggerProperties triggers = mock(TriggerProperties.class);
        when(triggers.getTriggers()).thenReturn(Map.of(
                "travel", List.of("旅游", "旅行", "出游", "路线", "攻略", "方案")));

        SkillRouter router = new SkillRouter(
                registry, store, policyFactory, mock(SkillLlmRouter.class), triggers);

        SkillRoutingResult result = router.route("不要坐车了，帮我生成一张水墨风风景图", "owner");

        assertEquals("transport", result.primarySkill(),
                "「怎么去/坐车」必须归 transport，travel 不得偷走");
        assertEquals(SkillRoutingResult.SkillRoutingAction.SWITCH, result.action());
    }

    @Test
    void imageSkillWinsInExplicitSwitchOverTransport() {
        // image（priority 5，默认策略）先于 transport（4）被遍历，且「生成一张」命中 image 自己的关键词
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition travel = skill("travel", null, 5);
        SkillDefinition image = skill("image", null, 5);
        SkillDefinition transport = skill("transport", "transportTriggerPolicy", 4);
        when(registry.getAll()).thenReturn(List.of(travel, image, transport));

        SkillSessionStore store = mock(SkillSessionStore.class);
        when(store.find("owner")).thenReturn(Optional.empty());

        TriggerPolicyFactory policyFactory = mock(TriggerPolicyFactory.class);
        SkillTriggerPolicy transportPolicy = policyMatching("坐车");
        when(policyFactory.getPolicy("transportTriggerPolicy")).thenReturn(transportPolicy);

        TriggerProperties triggers = mock(TriggerProperties.class);
        when(triggers.getTriggers()).thenReturn(Map.of(
                "travel", List.of("旅游", "旅行", "出游", "路线", "攻略", "方案"),
                "image", List.of("生成图片", "生成一张", "生成一幅", "帮我画", "给我画",
                        "画一张", "画一幅", "画个", "水墨", "绘图", "图片生成")));

        SkillRouter router = new SkillRouter(
                registry, store, policyFactory, mock(SkillLlmRouter.class), triggers);

        SkillRoutingResult result = router.route("不要坐车了，帮我生成一张水墨风风景图", "owner");

        assertEquals("image", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.SWITCH, result.action());
    }

    @Test
    void explicitSwitchStillRoutesToTargetSkill() {
        // 回归：显式切换（换/切到）语义不变。「换到天气」剥掉「换」后「到天气」命中 weather 关键词
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition travel = skill("travel", null, 5);
        SkillDefinition weather = skill("weather", "keywordTriggerPolicy", 2);
        when(registry.getAll()).thenReturn(List.of(travel, weather));

        SkillSessionStore store = mock(SkillSessionStore.class);
        when(store.find("owner")).thenReturn(Optional.empty());

        TriggerProperties triggers = mock(TriggerProperties.class);
        when(triggers.getTriggers()).thenReturn(Map.of(
                "travel", List.of("旅游", "旅行", "出游", "路线", "攻略", "方案"),
                "weather", List.of("天气", "气温", "多少度", "下雨", "下雪", "刮风", "降温")));

        SkillRouter router = new SkillRouter(
                registry, store, mock(TriggerPolicyFactory.class), mock(SkillLlmRouter.class), triggers);

        SkillRoutingResult result = router.route("换到天气", "owner");

        assertEquals("weather", result.primarySkill());
        assertEquals(SkillRoutingResult.SkillRoutingAction.SWITCH, result.action());
    }

    private SkillDefinition skill(String name, String triggerPolicyName, int priority) {
        SkillDefinition def = mock(SkillDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.triggerPolicyName()).thenReturn(triggerPolicyName);
        when(def.priority()).thenReturn(priority);
        return def;
    }

    private SkillTriggerPolicy policyMatching(String keyword) {
        SkillTriggerPolicy policy = mock(SkillTriggerPolicy.class);
        when(policy.match(any(), any())).thenAnswer(inv -> {
            String msg = inv.getArgument(0);
            return msg != null && msg.contains(keyword)
                    ? new SkillTriggerMatch(true, 0.85, keyword + " match", false)
                    : SkillTriggerMatch.noMatch();
        });
        return policy;
    }
}
