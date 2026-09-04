package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 transport 触发策略的置信度满足 SkillRouter 的路由门槛（>= 0.8）。
 *
 * <p>transport 技能只负责「交通方式 / 怎么去」类意图（打车叫车代驾已移除）。
 * 回归背景：此前「verb only」匹配返回 0.75，低于 {@code SkillRouter.route()}
 * 的 0.8 门槛，导致「怎么去鼋头渚」这类请求被丢弃，落回会话续接
 * （common），LLM 看不到任何交通方式/地图工具。
 */
class TransportTriggerPolicyTest {

    private final TransportTriggerPolicy policy = new TransportTriggerPolicy();

    @Test
    void testRouteToUnknownPlaceMatchesAtHighConfidence() {
        // 鼋头渚不在硬编码地点白名单里，走 verb-only 分支，但置信度必须 >= 0.8
        SkillTriggerMatch match = policy.match("怎么去鼋头渚", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8,
                "verb-only 交通请求置信度必须 >= 0.8（路由门槛），实际=" + match.confidence());
    }

    @Test
    void testBareTransitVerbMatchesAtHighConfidence() {
        SkillTriggerMatch match = policy.match("怎么去机场", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8);
    }

    @Test
    void testKnownPlacePlusVerbGetsEvenHigherConfidence() {
        SkillTriggerMatch match = policy.match("怎么去上海", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.85);
    }

    @Test
    void testRideBusVerbMatches() {
        SkillTriggerMatch match = policy.match("坐车去火车站", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8);
    }

    @Test
    void testRideHailingIntentDoesNotMatch() {
        // 打车/叫车/代驾 已从 transport 技能移除，不再路由
        assertFalse(policy.match("帮我打车去机场", Optional.empty()).matched());
        assertFalse(policy.match("帮我叫车", Optional.empty()).matched());
        assertFalse(policy.match("叫个代驾", Optional.empty()).matched());
    }

    @Test
    void testNonTransportDoesNotMatch() {
        SkillTriggerMatch match = policy.match("今天天气怎么样", Optional.empty());
        assertFalse(match.matched());
    }

    @Test
    void testNullMessageDoesNotMatch() {
        SkillTriggerMatch match = policy.match(null, Optional.empty());
        assertFalse(match.matched());
    }
}
