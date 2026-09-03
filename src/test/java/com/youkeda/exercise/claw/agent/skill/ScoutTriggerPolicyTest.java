package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutTriggerPolicyTest {

    @Test
    void acceptsMonitoringRequestsWithInfoStreamTopic() {
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("跟踪 Claude Code 的版本更新"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("以后关注考研政策变化"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("持续关注 AI Agent 的最新动态"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("帮我订阅科技资讯"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("帮我留意有什么最新动态"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("启动信息猎手"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("今天11:20帮我搜集一些关于AI的新闻"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("帮我收集一些最新的 AI 资讯"));
        assertTrue(ScoutTriggerPolicy.hasExplicitRequest("帮我汇总一下最近的行业动态"));
    }

    @Test
    void rejectsRealtimeLookupsEntitySubscriptionsAndInterestStatements() {
        // 回归：本次事故原句 —— 即时查询不得进后台任务
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("最近有什么热门科技新闻？"));
        // 即时查询（看/查/搜），无持续监控动词
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("帮我看看 AI Agent 最近有什么动态"));
        // 实体订阅：比赛/活动/番剧 不是信息流主题
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("关注一下这场比赛"));
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("订阅这个活动"));
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("关注《咒术回战》"));
        // 纯兴趣陈述、负向请求
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("我喜欢 AI Agent"));
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("查一下这个 bug 的原因"));
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("不要帮我查 AI 动态"));
        assertFalse(ScoutTriggerPolicy.hasExplicitRequest("我不想启动信息猎手"));
    }
}
