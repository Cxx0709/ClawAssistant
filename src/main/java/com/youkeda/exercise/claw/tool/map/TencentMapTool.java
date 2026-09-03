package com.youkeda.exercise.claw.tool.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.Tool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.map.*;
import com.youkeda.exercise.claw.domain.map.DistanceRequest;
import com.youkeda.exercise.claw.domain.map.PlaceSearchRequest;
import com.youkeda.exercise.claw.domain.map.RouteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯地图 LLM Function 入口
 *
 * <p>将腾讯地图能力以 LLM Function Calling 的方式暴露给 ReActAgentExecutor。
 * 注册三个函数：
 * <ul>
 *   <li>{@code map_search_place} — 地点搜索</li>
 *   <li>{@code map_route_planning} — 路线规划</li>
 *   <li>{@code map_distance_calculate} — 距离计算</li>
 * </ul>
 *
 * <p>函数注册后自动被 {@link ToolRegistry} 管理，
 * ReActAgentExecutor 在 tool-calling 循环中自动发现并调用。
 *
 * <p>本类自身 {@code extends AbstractTool} 仅为满足架构约束（tool 包类须实现 Tool 接口），
 * 作为「聚合注册器」并不把自身注册进 {@link ToolRegistry}，也不暴露给 LLM——
 * {@link #getName()} 返回的 {@code tencent_map_registry} 不在任何 skill 白名单中，
 * {@link #execute(String, ToolExecutionContext)} 永远不应被调用。
 */
@Component
public class TencentMapTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TencentMapTool.class);

    private final MapService mapService;

    public TencentMapTool(MapService mapService,
                               ObjectMapper objectMapper,
                               ToolRegistry functionRegistry) {
        super(functionRegistry, objectMapper);
        this.mapService = mapService;
    }

    @Override
    protected boolean shouldSelfRegister() {
        return false;
    }

    // ==== Tool 接口实现（仅为满足 tool 包类须实现 Tool 的架构约束，本类不注册自身）====

    @Override
    public String getName() {
        return "tencent_map_registry";
    }

    @Override
    public String getDescription() {
        return "腾讯地图能力聚合注册器（内部组件，非 LLM 工具）。";
    }

    @Override
    public JsonNode getParameters() {
        return schema().build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        return "{\"error\":\"tencent_map_registry 是内部注册器，请使用 map_search_place / "
                + "map_route_planning / map_distance_calculate\"}";
    }

    @Override
    protected void onInit() {
        // ==================== 1. 地点搜索 ====================
        registry.register(new AbstractTool(registry, objectMapper) {
            @Override
            protected boolean shouldSelfRegister() {
                return false;
            }

            @Override
            public String getName() {
                return "map_search_place";
            }

            @Override
            public String getDescription() {
                return "搜索指定地点，根据关键词和位置查找附近的景点、餐厅、酒店、活动场地等POI信息。" +
                        "适合用户问「附近有什么」或「推荐团建地点」时优先使用。返回结构化状态、地址和距离。" +
                        "如果状态为 EMPTY、PARTIAL 或 ERROR，再按 missing_information 使用 web_search 补充。";
            }

            @Override
            public JsonNode getParameters() {
                return schema()
                        .string("keyword", "搜索关键词，如：团建基地、餐厅、景点、酒店、户外拓展等", true)
                        .string("location", "位置，城市名称或区域名，如：无锡、上海、北京", false)
                        .build();
            }

            @Override
            public String execute(String argumentsJson, ToolExecutionContext context) {
                try {
                    JsonNode args = objectMapper.readTree(argumentsJson);
                    String keyword = args.path("keyword").asText("");
                    String location = args.path("location").asText("");

                    if (keyword.isBlank()) {
                        return errorResult("缺少必填参数: keyword");
                    }

                    log.info("TencentMapTool.map_search_place | keyword={} | location={}", keyword, location);
                    String data = mapService.searchPlace(new PlaceSearchRequest(keyword, location));
                    if (data.contains("未找到")) {
                        return result("EMPTY", data, List.of("地点候选"), true);
                    }
                    return result("SUCCESS", data,
                            List.of("团建项目详情", "团队价格", "开放时间和预约政策"), true);

                } catch (Exception e) {
                    log.error("map_search_place 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
                    return errorResult("地点搜索失败：" + e.getMessage());
                }
            }
        });

        // ==================== 2. 路线规划 ====================
        registry.register(new AbstractTool(registry, objectMapper) {
            @Override
            protected boolean shouldSelfRegister() {
                return false;
            }

            @Override
            public String getName() {
                return "map_route_planning";
            }

            @Override
            public String getDescription() {
                return "规划两个地点之间的路线。返回驾车距离、预计耗时和路线概览。" +
                        "适合用户问「怎么走」「怎么去」「路线」时使用。" +
                        "注意：参数地点名称会自动解析为坐标，无需事先调用地图编码。";
            }

            @Override
            public JsonNode getParameters() {
                ObjectNode mode = objectMapper.createObjectNode();
                mode.put("type", "string");
                mode.put("description", "出行方式，默认 driving（驾车）");
                mode.putArray("enum").add("driving").add("walking").add("transit");

                return schema()
                        .string("origin", "起点名称，如：无锡学院、拈花湾、灵山大佛", true)
                        .string("destination", "终点名称，如：拈花湾、灵山大佛、鼋头渚", true)
                        .raw("mode", mode, false)
                        .build();
            }

            @Override
            public String execute(String argumentsJson, ToolExecutionContext context) {
                try {
                    JsonNode args = objectMapper.readTree(argumentsJson);
                    String origin = args.path("origin").asText("");
                    String destination = args.path("destination").asText("");
                    String mode = args.path("mode").asText("driving");

                    if (origin.isBlank()) {
                        return errorResult("缺少必填参数: origin");
                    }
                    if (destination.isBlank()) {
                        return errorResult("缺少必填参数: destination");
                    }

                    log.info("TencentMapTool.map_route_planning | origin={} | destination={} | mode={}",
                            origin, destination, mode);
                    return result("SUCCESS", mapService.routePlanning(
                            new RouteRequest(origin, destination, mode)), List.of(), false);

                } catch (TencentMapException e) {
                    log.error("map_route_planning 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
                    return errorResult("路线规划失败：" + e.getMessage());
                } catch (Exception e) {
                    log.error("map_route_planning 执行异常 | args={} | error={}", argumentsJson, e.getMessage());
                    return errorResult("路线规划异常：" + e.getMessage());
                }
            }
        });

        // ==================== 3. 距离计算 ====================
        registry.register(new AbstractTool(registry, objectMapper) {
            @Override
            protected boolean shouldSelfRegister() {
                return false;
            }

            @Override
            public String getName() {
                return "map_distance_calculate";
            }

            @Override
            public String getDescription() {
                return "计算从一个地点到多个目的地的驾车距离并比较。" +
                        "适合用户问「哪个近」「哪个远」「距离比较」时使用。" +
                        "返回各地距离和推荐最近的目的地。";
            }

            @Override
            public JsonNode getParameters() {
                return schema()
                        .string("origin", "起点名称，如：无锡学院、酒店名称", true)
                        .arrayOfScalar("destinations", "多个目的地名称列表", "string", true)
                        .build();
            }

            @Override
            public String execute(String argumentsJson, ToolExecutionContext context) {
                try {
                    JsonNode args = objectMapper.readTree(argumentsJson);
                    String origin = args.path("origin").asText("");

                    List<String> destinations = new ArrayList<>();
                    JsonNode destArray = args.path("destinations");
                    if (destArray.isArray()) {
                        for (JsonNode item : destArray) {
                            String dest = item.asText("");
                            if (!dest.isBlank()) {
                                destinations.add(dest);
                            }
                        }
                    }

                    if (origin.isBlank()) {
                        return errorResult("缺少必填参数: origin");
                    }
                    if (destinations.isEmpty()) {
                        return errorResult("缺少必填参数: destinations");
                    }

                    log.info("TencentMapTool.map_distance_calculate | origin={} | destinations={}",
                            origin, destinations);
                    String data = mapService.calculateDistance(new DistanceRequest(origin, destinations));
                    if (data.startsWith("计算失败")) {
                        return result("EMPTY", data, List.of("候选地点距离和路线"), true);
                    }
                    if (data.contains("距离计算失败")) {
                        return result("PARTIAL", data, List.of("部分候选地点距离"), true);
                    }
                    return result("SUCCESS", data, List.of(), false);

                } catch (TencentMapException e) {
                    log.error("map_distance_calculate 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
                    return errorResult("距离计算失败：" + e.getMessage());
                } catch (Exception e) {
                    log.error("map_distance_calculate 执行异常 | args={} | error={}", argumentsJson, e.getMessage());
                    return errorResult("距离计算异常：" + e.getMessage());
                }
            }
        });

        log.info("TencentMapTool 已注册 3 个 LLM Function: map_search_place, map_route_planning, map_distance_calculate");
    }

    private String result(String status, String data, List<String> missingInformation,
                          boolean fallbackRequired) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", status);
        result.put("source", "TENCENT_MAP");
        result.put("data", data);
        ArrayNode missing = result.putArray("missing_information");
        missingInformation.forEach(missing::add);
        result.put("fallback_required", fallbackRequired);
        return result.toString();
    }

    private String errorResult(String message) {
        return result("ERROR", message, List.of("地图信息"), true);
    }
}
