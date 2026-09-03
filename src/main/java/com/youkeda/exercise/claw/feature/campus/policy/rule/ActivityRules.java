package com.youkeda.exercise.claw.feature.campus.policy.rule;

import java.util.List;

/**
 * 活动通知推送规则。
 * 大型全校活动自动推送，其他活动询问用户。
 */
public class ActivityRules implements PolicyRule {

    /** 自动推送的大型活动类型 */
    private static final List<String> AUTO_PUSH = List.of("CAMPUS_EVENT");

    @Override
    public List<String> getAutoPushTypes() {
        return AUTO_PUSH;
    }
}
