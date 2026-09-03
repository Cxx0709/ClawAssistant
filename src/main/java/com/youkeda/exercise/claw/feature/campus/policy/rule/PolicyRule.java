package com.youkeda.exercise.claw.feature.campus.policy.rule;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;

import java.util.List;

/**
 * 推送规则提供者接口。
 * 每个 Source 提供一个实现，定义哪些类型应该自动推送。
 */
public interface PolicyRule {

    /** 返回此 Source 应自动推送的类型列表 */
    List<String> getAutoPushTypes();

    /** 判断某条通知是否应自动推送 */
    default boolean shouldAutoNotify(NotificationItem item) {
        return getAutoPushTypes().contains(item.getType());
    }
}
