package com.youkeda.exercise.claw.feature.goal;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GoalReplyGuardTest {

    private GoalReplyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new GoalReplyGuard();
    }

    @Test
    void returnsGrowthGoalSkillName() {
        assertEquals("growth-goal", guard.getSkillName());
    }

    @Test
    void blocksClaimWithoutToolCall() {
        GuardResult r = validate("建立一个比赛目标", "已帮你创建好目标了", Set.of(), Map.of());
        assertFalse(r.allowed(), "声称已创建但未调用工具时应拦截");
        assertNotNull(r.correction());
    }

    @Test
    void blocksClaimWhenToolFailed() {
        Set<String> executed = new HashSet<>();
        executed.add("goal_manage|{\"action\":\"create\"}");
        GuardResult r = validate("建立一个比赛目标", "已帮你创建好目标了",
                executed, Map.of("goal_manage", ResultStatus.FAILED));
        assertFalse(r.allowed(), "工具调用失败时不应声称成功");
    }

    @Test
    void allowsClaimWhenToolSucceeded() {
        Set<String> executed = new HashSet<>();
        executed.add("goal_manage|{\"action\":\"create\",\"title\":\"比赛\"}");
        GuardResult r = validate("建立一个比赛目标", "已帮你创建好目标了",
                executed, Map.of("goal_manage", ResultStatus.SUCCESS));
        assertTrue(r.allowed(), "工具成功时应放行");
    }

    @Test
    void allowsClarificationQuestion() {
        GuardResult r = validate("帮我建立目标", "你想把什么作为目标呢？", Set.of(), Map.of());
        assertTrue(r.allowed(), "追问时应放行");
    }

    @Test
    void allowsListQuery() {
        GuardResult r = validate("我有哪些目标", "你目前有2个活跃目标", Set.of(), Map.of());
        assertTrue(r.allowed(), "查询目标列表应放行");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "已帮你创建好目标",
            "已为你创建了成长目标",
            "已推进目标进度",
            "已更新目标",
            "已帮你取消目标",
            "已完成目标",
            "目标创建完成",
            "goal_id=42"
    })
    void detectsVariousClaimPatterns(String reply) {
        GuardResult r = validate("建立目标", reply, Set.of(), Map.of());
        assertFalse(r.allowed(), "应检测到声称模式: " + reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你想达成什么目标？",
            "我可以帮你建立一个成长目标",
            "目前你有两个活跃目标：1.考研 2.比赛",
            "目标进度如何？"
    })
    void doesNotFalsePositiveOnNonClaims(String reply) {
        GuardResult r = validate("帮我建立目标", reply, Set.of(), Map.of());
        assertTrue(r.allowed(), "非声称回复不应被拦截: " + reply);
    }

    private GuardResult validate(String userMsg, String reply,
                                  Set<String> executedCalls,
                                  Map<String, ResultStatus> toolStatuses) {
        return guard.validate(new GuardContext(
                userMsg, reply, SkillSession.create("u"), executedCalls, toolStatuses));
    }
}
