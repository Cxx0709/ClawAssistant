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
        // travel_collect 返回 NEED_MORE_INFORMATION（Parser 解析为 PARTIAL）→ 只能追问
        GuardResult r = validate("我要去三亚", "已规划好了完整行程",
                Map.of("travel_collect", ResultStatus.PARTIAL));
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

    @Test
    void allowsPlanWhenAllCollectedMappedToSuccess() {
        // 修复前 ALL_COLLECTED 被解析为 FAILED，导致正则命中后误拦
        GuardResult r = validate("帮我规划行程", "已为你规划好三亚两日游方案：Day 1...",
                Map.of("travel_collect", ResultStatus.SUCCESS));
        assertTrue(r.allowed());
    }

    @Test
    void allowsBudgetConclusionAfterPartialCost() {
        // calculate_cost 返回 PARTIAL（如酒店价格缺失）时不应强制拦截金额描述
        GuardResult r = validate("帮我规划行程", "已为你规划好方案，总费用约为 1000 元，部分住宿待确认",
                Map.of(
                        "travel_collect", ResultStatus.SUCCESS,
                        "travel_calculate_cost", ResultStatus.PARTIAL
                ));
        assertTrue(r.allowed());
    }

    @Test
    void stillBlocksBudgetConclusionWithoutCostTool() {
        GuardResult r = validate("帮我规划行程", "总费用约 1200 元",
                Map.of("travel_collect", ResultStatus.SUCCESS));
        assertFalse(r.allowed());
    }
}
