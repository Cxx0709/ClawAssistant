package com.youkeda.exercise.claw.feature.scout;

/**
 * 信息猎手执行报告
 */
public record ScoutReport(
        int tasksGenerated,
        int itemsCollected,
        int recommendations
) {}
