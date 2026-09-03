package com.youkeda.exercise.claw.feature.scout.planner;

import java.util.UUID;

/**
 * 搜索任务
 *
 * 由 SearchPlanner 根据用户画像动态生成
 */
public record SearchTask(
        String id,
        String query,
        String category,
        String reason,
        int priority
) {

    public static SearchTask of(String query, String category, String reason, int priority) {
        return new SearchTask(UUID.randomUUID().toString(), query, category, reason, priority);
    }

    /** 信息分类常量 */
    public static final String NEWS = "NEWS";
    public static final String BLOG = "BLOG";
    public static final String GITHUB = "GITHUB";
    public static final String JOB = "JOB";
    public static final String COMPETITION = "COMPETITION";
}
