package com.youkeda.exercise.claw.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScheduleIntentResolver} 意图解析测试。
 *
 * <p>核心回归：旧实现因命中裸「定时」把「我有哪些定时提醒」误判为创建，
 * 导致防幻觉 guard 注入提示死循环 15 轮。动词驱动后查询/修改/取消不再误伤。
 */
class ScheduleIntentResolverTest {

    @Test
    void detectsCreateIntents() {
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("每天8点提醒我喝水"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("帮我设置提醒"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("10分钟后提醒我提交代码"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("设置定时提醒"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("每周一早上9点提醒我写周报"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("每天早上8点喝水"));
        assertEquals(ScheduleIntent.CREATE, ScheduleIntentResolver.resolve("帮我定个闹钟"));
    }

    @Test
    void detectsQueryIntents() {
        // 回归用例：旧实现命中「定时」误判为创建
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("我有哪些定时提醒？"));
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("查看我的提醒列表"));
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("查询一下我有什么提醒"));
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("我的提醒有哪些"));
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("看看我设置的提醒"));
    }

    @Test
    void detectsUpdateIntents() {
        assertEquals(ScheduleIntent.UPDATE, ScheduleIntentResolver.resolve("把喝水提醒改成晚上9点"));
        assertEquals(ScheduleIntent.UPDATE, ScheduleIntentResolver.resolve("修改提醒时间"));
        assertEquals(ScheduleIntent.UPDATE, ScheduleIntentResolver.resolve("把8点的提醒调整到9点"));
    }

    @Test
    void detectsDeleteIntents() {
        assertEquals(ScheduleIntent.DELETE, ScheduleIntentResolver.resolve("取消喝水提醒"));
        assertEquals(ScheduleIntent.DELETE, ScheduleIntentResolver.resolve("删除追番提醒"));
    }

    @Test
    void returnsNoneForUnrelatedMessages() {
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve("今天北京天气怎么样"));
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve(null));
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve(""));
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve("   "));
        // 疑问句（创建动词出现在对象之后）不得误判为创建，否则 guard 重试死循环
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve("定时提醒怎么设置"));
        assertEquals(ScheduleIntent.NONE, ScheduleIntentResolver.resolve("定时提醒是什么"));
    }

    @Test
    void actionVerbWinsOverCreateOnCompoundMessages() {
        // 取消优先于「提醒我」
        assertEquals(ScheduleIntent.DELETE, ScheduleIntentResolver.resolve("取消提醒我明天开会"));
        // 查询优先于创建动词：用户询问已有提醒，不是创建
        assertEquals(ScheduleIntent.QUERY, ScheduleIntentResolver.resolve("提醒我有哪些定时提醒"));
    }
}
