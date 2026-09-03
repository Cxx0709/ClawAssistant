package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TravelReplyGuardTest {

    private final TravelReplyGuard guard = new TravelReplyGuard();

    private SkillReplyGuard.GuardResult validate(String userMsg, String reply,
                                                 Map<String, ResultStatus> statuses) {
        return guard.validate(new SkillReplyGuard.GuardContext(
                userMsg, reply, SkillSession.create("u"), Set.of(), statuses));
    }

    @Test
    void blocksCompletedPlanWhenCollectNotCalled() {
        // 旅行请求，回复声称已完成行程，但 travel_collect 从未调用（map 为空）→ 拦截
        GuardResult r = validate("我要去三亚玩三天", "已为你规划好三亚三天游行程：Day 1...",
                Map.of());
        assertFalse(r.allowed());
        assertNotNull(r.correction());
    }

    @Test
    void blocksCompletedPlanWhenCollectNeedsMoreInfo() {
        // travel_collect 返回 NEED_MORE_INFORMATION（Parser 解析为 FAILED）→ 只能追问
        GuardResult r = validate("我要去三亚", "已规划好了完整行程",
                Map.of("travel_collect", ResultStatus.FAILED));
        assertFalse(r.allowed());
    }

    @Test
    void allowsCollectingMoreInfoReply() {
        // 回复是追问，且 travel_collect 已执行（PARTIAL 表示仍在收集）→ 放行
        GuardResult r = validate("我要去三亚", "请问一共几个人去呢？",
                Map.of("travel_collect", ResultStatus.PARTIAL));
        assertTrue(r.allowed());
    }

    @Test
    void blocksBudgetConclusionWithoutCostTool() {
        // 回复含预算结论，但未调 travel_calculate_cost → 拦截
        GuardResult r = validate("我要去三亚玩三天，预算五千",
                "总费用约 4800 元，在预算内", Map.of());
        assertFalse(r.allowed());
    }

    @Test
    void allowsReplyAfterCostCalculated() {
        GuardResult r = validate("我要去三亚玩三天", "总费用 4800 元",
                Map.of("travel_calculate_cost", ResultStatus.SUCCESS));
        assertTrue(r.allowed());
    }

    @Test
    void allowsNonTravelGeneralReply() {
        // 回复不含行程完成词、不含预算结论，且无 travel 请求 → 放行
        GuardResult r = validate("三亚现在天气怎么样", "三亚今天晴。",
                Map.of());
        assertTrue(r.allowed());
    }
}
