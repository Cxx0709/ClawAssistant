package com.youkeda.exercise.claw.feature.campus.policy;

import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.feature.campus.policy.rule.PolicyRule;

public interface NotificationPolicy {

    enum Decision {
        NOTIFY,  // 自动推送
        ASK,     // 询问用户是否要推
        SKIP,    // 用户之前说不需要
        IGNORE   // 非通知，直接忽略
    }

    /**
     * 对一条已分类的通知做出推送决策
     *
     * @param item   已分类的通知
     * @param config 全局配置（含 source 开关）
     * @param rule   当前 Source 的推送规则
     * @return 决策结果
     */
    Decision decide(NotificationItem item, CampusConfig config, PolicyRule rule);
}
