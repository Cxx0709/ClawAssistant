package com.youkeda.exercise.claw.feature.campus.policy.rule;

import java.util.List;

/**
 * 就业通知推送规则。
 * 招聘会/双选会/名企宣讲自动推送，其他就业信息询问用户。
 */
public class JobRules implements PolicyRule {

    /** 自动推送的就业类型 */
    private static final List<String> AUTO_PUSH = List.of("CAREER_FAIR", "ELITE_TALK");

    @Override
    public List<String> getAutoPushTypes() {
        return AUTO_PUSH;
    }
}
