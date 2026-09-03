package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 transport 触发策略的置信度满足 SkillRouter 的路由门槛（>= 0.8）。
 *
 * <p>回归背景：此前「verb only」匹配返回 0.75，低于 {@code SkillRouter.route()}
 * 的 0.8 门槛，导致「帮我打车去鼋头渚」这类请求被丢弃，落回会话续接
 * （information-scout / common），LLM 看不到任何打车/地图工具。
 */
class TransportTriggerPolicyTest {

    private final TransportTriggerPolicy policy = new TransportTriggerPolicy();

    @Test
    void testTaxiToArbitraryPlaceRoutesAtHighConfidence() {
        // 鼋头渚不在硬编码地点白名单里，走 verb-only 分支，但置信度必须 >= 0.8
        SkillTriggerMatch match = policy.match("帮我打车去鼋头渚", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8,
                "verb-only 交通请求置信度必须 >= 0.8（路由门槛），实际=" + match.confidence());
    }

    @Test
    void testBareTaxiVerbRoutesAtHighConfidence() {
        SkillTriggerMatch match = policy.match("帮我打车", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8);
    }

    @Test
    void testKnownPlacePlusVerbGetsEvenHigherConfidence() {
        SkillTriggerMatch match = policy.match("打车去上海", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.85);
    }

    @Test
    void testCallRideVerbMatches() {
        SkillTriggerMatch match = policy.match("帮我叫车去火车站", Optional.empty());
        assertTrue(match.matched());
        assertTrue(match.confidence() >= 0.8);
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
