package com.youkeda.exercise.claw.feature.task;

import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleReplyGuardTest {

    private final ScheduleReplyGuard guard = new ScheduleReplyGuard();

    @Test
    void injectsHintWhenModelClaimsCreatedButToolNotCalled() {
        // 用户要创建 + 回复声称「已创建」+ 未调用 create_schedule_task → 幻觉，注入提示
        GuardResult r = guard.validate(new GuardContext(
                "帮我设置提醒", "已为你创建好了明天8点的提醒",
                SkillSession.create("u"), new HashSet<>(), Map.of()));
        assertFalse(r.allowed(), "声称已创建但未调用工具时应拦截");
        assertNotNull(r.correction(), "拦截时需给出修正提示");
    }

    @Test
    void allowsClarifyingQuestion() {
        // 模型反问「几点提醒你呢？」属澄清，放行（不注入）
        GuardResult r = guard.validate(new GuardContext(
                "帮我设置提醒", "好的，请问几点提醒你呢？",
                SkillSession.create("u"), new HashSet<>(), Map.of()));
        assertTrue(r.allowed(), "向用户澄清时应放行，避免死循环");
    }

    @Test
    void allowsNonCreateIntent() {
        // 用户只是查询，非创建意图，guard 不干预
        GuardResult r = guard.validate(new GuardContext(
                "我有哪些提醒", "你有3条提醒",
                SkillSession.create("u"), new HashSet<>(), Map.of()));
        assertTrue(r.allowed(), "非创建意图不应触发 guard");
    }

    @Test
    void allowsWhenToolWasActuallyCalled() {
        // executedCalls 已含 create_schedule_task 签名 → 放行
        Set<String> executed = new HashSet<>();
        executed.add("create_schedule_task|{\"content\":\"喝水\"}");
        GuardResult r = guard.validate(new GuardContext(
                "帮我设置提醒", "已为你创建好了喝水提醒",
                SkillSession.create("u"), executed, Map.of()));
        assertTrue(r.allowed(), "已实际调用创建工具时应放行");
    }
}
