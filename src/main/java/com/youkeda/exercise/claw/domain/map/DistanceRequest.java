package com.youkeda.exercise.claw.domain.map;

import java.util.List;

/**
 * 距离计算请求参数
 *
 * <p>封装 LLM 调用 {@code map_distance_calculate} 函数时传入的参数。
 * 计算从起点到多个目的地的距离，用于比较哪个目的地更近。
 *
 * @param origin       起点名称，如"无锡学院"
 * @param destinations 多个目的地名称列表，如["拈花湾", "灵山"]
 */
public record DistanceRequest(String origin, List<String> destinations) {

    /**
     * 是否为有效的距离计算请求
     */
    public boolean isValid() {
        return origin != null && !origin.isBlank()
                && destinations != null && !destinations.isEmpty();
    }
}