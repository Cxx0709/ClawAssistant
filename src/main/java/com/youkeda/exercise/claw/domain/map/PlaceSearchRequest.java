package com.youkeda.exercise.claw.domain.map;

/**
 * 地点搜索请求参数
 *
 * <p>封装 LLM 调用 {@code map_search_place} 函数时传入的参数。
 *
 * @param keyword  搜索关键词，如"团建基地"、"户外拓展"
 * @param location 位置限定，如"无锡"、"上海"
 */
public record PlaceSearchRequest(String keyword, String location) {

    /**
     * 是否为有效的搜索请求
     */
    public boolean isValid() {
        return keyword != null && !keyword.isBlank();
    }
}