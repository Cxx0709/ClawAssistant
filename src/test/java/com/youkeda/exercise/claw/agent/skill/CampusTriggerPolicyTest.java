package com.youkeda.exercise.claw.agent.skill;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CampusTriggerPolicyTest {
    private final CampusTriggerPolicy policy = new CampusTriggerPolicy();

    @Test void personalScheduleRequestsMatch() {
        for (String request : new String[]{"导入课表", "明天有哪些课", "登记考试", "绑定学校", "设置学期"}) {
            assertTrue(policy.match(request, Optional.empty()).confidence() >= 0.8, request);
        }
    }

    @Test void recommendationsAndOrdinaryFilesDoNotMatch() {
        for (String request : new String[]{"推荐一些机器学习课程", "介绍一下这所学校", "分析这个PDF", "课程是什么"}) {
            assertFalse(policy.match(request, Optional.empty()).matched(), request);
        }
    }

    @Test void confirmationNeedsActiveCampusSession() {
        assertFalse(policy.match("确认", Optional.empty()).matched());
        assertTrue(policy.match("确认", Optional.of(SkillSession.create("u").withActiveSkill("campus"))).matched());
    }
}
