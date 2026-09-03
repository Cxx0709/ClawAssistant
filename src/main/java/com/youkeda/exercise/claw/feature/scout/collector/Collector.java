package com.youkeda.exercise.claw.feature.scout.collector;

import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;

import java.util.List;

/**
 * 信息采集器接口
 */
public interface Collector {

    /**
     * 采集器类型标识
     */
    String getType();

    /**
     * 执行采集
     *
     * @param task 搜索任务
     * @return 采集到的信息列表
     */
    List<InformationItem> collect(SearchTask task);
}
