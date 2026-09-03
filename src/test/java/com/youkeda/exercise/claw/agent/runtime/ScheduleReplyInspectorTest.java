package com.youkeda.exercise.claw.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScheduleReplyInspector} 回复检查测试。
 *
 * <p>防幻觉 guard 从「用户意图 CREATE + 未调工具即拦截」升级为：
 * <ol>
 *   <li>声称已创建但未调工具 → 幻觉，拦截重试</li>
 *   <li>创建意图但既未声称完成、也未向用户澄清 → 卡壳，提示补做</li>
 *   <li>创建意图 + 反问澄清「几点提醒你呢？」→ 放行，不死循环</li>
 * </ol>
 */
class ScheduleReplyInspectorTest {

    // ============ claimsCreation：是否声称已完成创建/设置 ============

    @Test
    void detectsCompletionClaims() {
        assertTrue(ScheduleReplyInspector.claimsCreation("✅ 喝水提醒已设置好啦！"));
        assertTrue(ScheduleReplyInspector.claimsCreation("好的，已帮你创建好喝水提醒"));
        assertTrue(ScheduleReplyInspector.claimsCreation("创建成功了"));
        assertTrue(ScheduleReplyInspector.claimsCreation("搞定，已经安排好了"));
        assertTrue(ScheduleReplyInspector.claimsCreation("提醒已设置，每天20:30"));
        assertTrue(ScheduleReplyInspector.claimsCreation("我已经帮你设置好提醒了"));
    }

    @Test
    void doesNotDetectPromisesOrQuestionsAsClaims() {
        assertFalse(ScheduleReplyInspector.claimsCreation("好的，我马上帮你设置"));
        assertFalse(ScheduleReplyInspector.claimsCreation("好的"));
        assertFalse(ScheduleReplyInspector.claimsCreation("几点提醒你呢？"));
        assertFalse(ScheduleReplyInspector.claimsCreation("请问您希望什么时候提醒？"));
    }

    @Test
    void doesNotDetectNegatedOrFailedClaims() {
        assertFalse(ScheduleReplyInspector.claimsCreation("抱歉，还没有创建成功"));
        assertFalse(ScheduleReplyInspector.claimsCreation("无法创建提醒"));
        assertFalse(ScheduleReplyInspector.claimsCreation("创建失败了，请重试"));
    }

    @Test
    void returnsFalseForBlankReplies() {
        assertFalse(ScheduleReplyInspector.claimsCreation(null));
        assertFalse(ScheduleReplyInspector.claimsCreation(""));
        assertFalse(ScheduleReplyInspector.claimsCreation("   "));
    }

    // ============ asksForClarification：是否在向用户澄清信息 ============

    @Test
    void detectsClarifyingQuestions() {
        assertTrue(ScheduleReplyInspector.asksForClarification("几点提醒你呢？"));
        assertTrue(ScheduleReplyInspector.asksForClarification("请问您希望什么时候提醒？"));
        assertTrue(ScheduleReplyInspector.asksForClarification("你想几点提醒"));
        assertTrue(ScheduleReplyInspector.asksForClarification("请告诉我提醒内容"));
        assertTrue(ScheduleReplyInspector.asksForClarification("您希望设置在几点呢"));
    }

    @Test
    void doesNotDetectNonClarifyingReplies() {
        assertFalse(ScheduleReplyInspector.asksForClarification("好的，设置好了"));
        assertFalse(ScheduleReplyInspector.asksForClarification("好的"));
        assertFalse(ScheduleReplyInspector.asksForClarification("马上帮你设置"));
        assertFalse(ScheduleReplyInspector.asksForClarification(null));
        assertFalse(ScheduleReplyInspector.asksForClarification(""));
    }
}
