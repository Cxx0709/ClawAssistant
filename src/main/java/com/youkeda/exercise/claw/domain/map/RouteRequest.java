package com.youkeda.exercise.claw.domain.map;

/**
 * 路线规划请求参数
 *
 * <p>封装 LLM 调用 {@code map_route_planning} 函数时传入的参数。
 *
 * @param origin      起点名称，如"无锡学院"
 * @param destination 终点名称，如"拈花湾"
 * @param mode        出行方式：driving（驾车）/ walking（步行）/ transit（公交）
 */
public record RouteRequest(String origin, String destination, String mode) {

    /**
     * 是否为有效的路线规划请求
     */
    public boolean isValid() {
        return origin != null && !origin.isBlank()
                && destination != null && !destination.isBlank();
    }

    /**
     * 获取标准化的出行方式，默认 driving
     */
    public String normalizedMode() {
        if (mode == null) return "driving";
        return switch (mode.trim().toLowerCase()) {
            case "walking", "步行" -> "walking";
            case "transit", "公交", "地铁" -> "transit";
            default -> "driving";
        };
    }
}