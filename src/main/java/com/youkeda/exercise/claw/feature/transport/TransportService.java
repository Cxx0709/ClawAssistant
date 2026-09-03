package com.youkeda.exercise.claw.feature.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.map.MapService;
import com.youkeda.exercise.claw.domain.map.RouteRequest;
import com.youkeda.exercise.claw.domain.transport.TransportOption;
import com.youkeda.exercise.claw.domain.transport.TransportRequest;
import com.youkeda.exercise.claw.domain.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交通方式推荐服务
 *
 * <p>核心链路：
 * <ol>
 *   <li>调用 {@link MapService#routePlanning} 获取出发地到目的地的驾车距离和耗时</li>
 *   <li>调用 {@link TransportCalculator} 计算各交通方式的费用、耗时、运力</li>
 *   <li>调用 {@link TransportDecisionEngine} 给出推荐结论和推荐理由</li>
 *   <li>序列化为结构化 JSON 返回给 LLM</li>
 * </ol>
 *
 * <p>所有价格计算由 Java 完成，不依赖 LLM。
 */
@Service
public class TransportService {

    private static final Logger log = LoggerFactory.getLogger(TransportService.class);

    /** 默认驾车耗时（秒），当地图 API 不可用时使用 */
    private static final int DEFAULT_DRIVING_DURATION_SECONDS = 3600;

    private final MapService mapService;
    private final TransportCalculator calculator;
    private final TransportDecisionEngine decisionEngine;
    private final ObjectMapper objectMapper;

    /** 解析 MapService.routePlanning 返回文本中的距离（米）和耗时（秒） */
    private static final Pattern DISTANCE_PATTERN = Pattern.compile("约?(\\d+(?:\\.\\d+)?)(?:公里|千米|km)");
    private static final Pattern DURATION_HOUR_MIN_PATTERN = Pattern.compile("(\\d+)小时(\\d+)分钟");
    private static final Pattern DURATION_HOUR_PATTERN = Pattern.compile("(\\d+)小时");
    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile("(\\d+)分钟");

    public TransportService(MapService mapService,
                            TransportCalculator calculator,
                            TransportDecisionEngine decisionEngine,
                            ObjectMapper objectMapper) {
        this.mapService = mapService;
        this.calculator = calculator;
        this.decisionEngine = decisionEngine;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行交通方式推荐
     *
     * @param request 交通推荐请求（出发地、目的地、人数、预算）
     * @return 结构化 JSON 结果字符串
     */
    public String recommend(TransportRequest request) {
        String from = request.getFrom();
        String to = request.getTo();
        int people = request.getPeople();
        log.info("交通方式推荐 | from={} | to={} | people={} | budget={}",
                from, to, people, request.getBudget());

        // Step 1: 获取驾车距离和耗时（通过腾讯地图路线规划）
        double distanceMeters = 0;
        int drivingDurationSeconds = DEFAULT_DRIVING_DURATION_SECONDS;
        try {
            String routeText = mapService.routePlanning(new RouteRequest(from, to, "driving"));
            distanceMeters = parseDistanceFromText(routeText);
            drivingDurationSeconds = parseDurationFromText(routeText);
            log.info("路线规划获取成功 | distanceMeters={} | durationSeconds={}",
                    distanceMeters, drivingDurationSeconds);
        } catch (Exception e) {
            log.warn("路线规划失败，使用默认参数估算 | from={} | to={} | error={}",
                    from, to, e.getMessage());
            // 降级：使用预设直线距离估算
            distanceMeters = estimateDistance(from, to);
            drivingDurationSeconds = (int) (distanceMeters / 1000.0 / 80.0 * 3600); // 按 80km/h 估算
        }

        double distanceKm = distanceMeters / 1000.0;
        int drivingMinutes = (int) (drivingDurationSeconds / 60.0);

        // Step 2: 计算各交通方式费用、耗时、运力
        List<TransportOption> options = calculator.calculate(distanceKm, drivingMinutes, people);

        // Step 3: 决策推荐（含推荐理由）
        TransportDecisionEngine.DecisionResult decision =
                decisionEngine.decide(distanceKm, people, options, request.getBudget());

        // Step 4: 构建结果
        TransportResult result = new TransportResult();
        result.setFrom(from);
        result.setTo(to);
        result.setPeople(people);
        result.setDistanceMeters((int) distanceMeters);
        result.setDistanceKm(Math.round(distanceKm * 100.0) / 100.0);
        result.setDrivingDurationSeconds(drivingDurationSeconds);
        result.setOptions(options);
        result.setRecommendation(decision.recommendation);
        result.setRecommendationReason(decision.reason);
        result.setBudget(request.getBudget());

        return toJson(result);
    }

    /**
     * 将结果序列化为结构化 JSON
     */
    private String toJson(TransportResult result) {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            // 概要
            root.put("from", result.getFrom());
            root.put("to", result.getTo());
            root.put("people", result.getPeople());
            root.put("distance_km", result.getDistanceKm());
            root.put("driving_duration_text", TransportCalculator.formatDuration(
                    (int) (result.getDrivingDurationSeconds() / 60)));
            if (result.getBudget() != null) {
                root.put("budget", result.getBudget());
            }

            // 各交通方式对比
            ArrayNode optionsArray = objectMapper.createArrayNode();
            for (TransportOption opt : result.getOptions()) {
                ObjectNode optNode = objectMapper.createObjectNode();
                optNode.put("type", opt.getType());
                optNode.put("type_name", opt.getTypeName());
                optNode.put("total_cost", opt.getTotalCost());
                optNode.put("per_person_cost", opt.getPerPersonCost());
                optNode.put("duration_minutes", opt.getDurationMinutes());
                optNode.put("duration_text", opt.getDurationText());
                optNode.put("distance_km", opt.getDistanceKm());
                optNode.put("capacity", opt.getCapacity());
                if (opt.getVehicleCount() > 0) {
                    optNode.put("vehicle_count", opt.getVehicleCount());
                }
                optNode.put("advantage", opt.getAdvantage());
                optionsArray.add(optNode);
            }
            root.set("options", optionsArray);

            // 推荐结论
            root.put("recommendation", result.getRecommendation());
            root.put("recommendation_reason", result.getRecommendationReason());

            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "{\"error\": \"结果序列化失败\"}";
        }
    }

    // ==================== 文本解析工具 ====================

    /**
     * 从路线规划文本中解析距离（米）
     * <p>例："约12公里" → 12000
     */
    static double parseDistanceFromText(String routeText) {
        Matcher m = DISTANCE_PATTERN.matcher(routeText);
        if (m.find()) {
            return Double.parseDouble(m.group(1)) * 1000;
        }
        return 0;
    }

    /**
     * 从路线规划文本中解析耗时（秒）
     * <p>例："预计1小时30分钟" → 5400
     */
    static int parseDurationFromText(String routeText) {
        Matcher hm = DURATION_HOUR_MIN_PATTERN.matcher(routeText);
        if (hm.find()) {
            int hours = Integer.parseInt(hm.group(1));
            int minutes = Integer.parseInt(hm.group(2));
            return hours * 3600 + minutes * 60;
        }
        Matcher h = DURATION_HOUR_PATTERN.matcher(routeText);
        if (h.find()) {
            return Integer.parseInt(h.group(1)) * 3600;
        }
        Matcher m = DURATION_MIN_PATTERN.matcher(routeText);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) * 60;
        }
        return DEFAULT_DRIVING_DURATION_SECONDS;
    }

    /**
     * 简单距离估算（各地城市间大致的直线距离）
     * <p>当腾讯地图 API 不可用时使用
     */
    private double estimateDistance(String from, String to) {
        String key = (from + "→" + to).toLowerCase();
        return switch (key) {
            case "上海→杭州", "杭州→上海" -> 175_000;
            case "上海→南京", "南京→上海" -> 300_000;
            case "上海→北京", "北京→上海" -> 1_200_000;
            case "杭州→南京", "南京→杭州" -> 260_000;
            case "北京→天津", "天津→北京" -> 130_000;
            case "广州→深圳", "深圳→广州" -> 140_000;
            case "上海→苏州", "苏州→上海" -> 100_000;
            default -> 300_000; // 默认 300km
        };
    }
}