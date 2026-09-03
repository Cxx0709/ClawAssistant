package com.youkeda.exercise.claw.feature.map;

import com.youkeda.exercise.claw.feature.map.TencentMapClient.DirectionResult;
import com.youkeda.exercise.claw.feature.map.TencentMapClient.GeoPoint;
import com.youkeda.exercise.claw.feature.map.TencentMapClient.PoiResult;
import com.youkeda.exercise.claw.domain.map.DistanceRequest;
import com.youkeda.exercise.claw.domain.map.PlaceSearchRequest;
import com.youkeda.exercise.claw.domain.map.RouteRequest;
import com.youkeda.exercise.claw.domain.map.PlaceResponse;
import com.youkeda.exercise.claw.domain.map.RouteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 地图业务服务层
 *
 * <p>提供地点搜索、路线规划、距离计算等空间智能能力。
 * 封装 {@link TencentMapClient} 的调用，将原始 API 结果处理为 LLM 理解的文本。
 */
@Service
public class MapService {

    private static final Logger log = LoggerFactory.getLogger(MapService.class);

    private final TencentMapClient mapClient;

    public MapService(TencentMapClient mapClient) {
        this.mapClient = mapClient;
    }

    /**
     * 地点搜索：根据关键词和位置搜索 POI
     *
     * @param request 搜索请求（关键词、位置限定）
     * @return 格式化的自然语言文本结果
     */
    public String searchPlace(PlaceSearchRequest request) {
        log.info("地点搜索 | keyword={} | location={}", request.keyword(), request.location());

        // 地点搜索：searchPoi 内部以 region(城市) 文本限定边界，无需先 geocode 坐标
        // （此前此处 geocode 结果 center 从未被使用，属多余 API 调用，已移除）
        List<PoiResult> poiResults = mapClient.searchPoi(request.keyword(), request.location());

        if (poiResults.isEmpty()) {
            return "在" + (request.location() != null ? request.location() : "当前位置") + "附近未找到与「" + request.keyword() + "」相关的地点。";
        }

        // 转换为 PlaceResponse 并格式化
        List<PlaceResponse> places = poiResults.stream()
                .map(this::toPlaceResponse)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("找到").append(places.size()).append("个候选地点：\n\n");
        for (int i = 0; i < places.size(); i++) {
            sb.append(places.get(i).toTextLine(i + 1));
            sb.append("\n\n");
        }

        log.info("地点搜索完成 | 结果数={}", places.size());
        return sb.toString().trim();
    }

    /**
     * 路线规划：规划两个地点之间的路线
     *
     * <p>完整流程：
     * 1. 地理编码起点名称 → 经纬度
     * 2. 地理编码终点名称 → 经纬度
     * 3. 调用路线规划 API
     * 4. 封装为自然语言文本
     *
     * @param request 路线规划请求（起点、终点、出行方式）
     * @return 格式化的路线规划文本
     */
    public String routePlanning(RouteRequest request) {
        log.info("路线规划 | origin={} | destination={} | mode={}",
                request.origin(), request.destination(), request.normalizedMode());

        // Step 1: 地理编码起点
        GeoPoint fromPoint = mapClient.geocode(request.origin());

        // Step 2: 地理编码终点
        GeoPoint toPoint = mapClient.geocode(request.destination());

        // Step 3: 调用路线规划 API
        DirectionResult direction = mapClient.direction(
                fromPoint.lat(), fromPoint.lng(),
                toPoint.lat(), toPoint.lng(),
                request.normalizedMode());

        // Step 4: 封装结果
        RouteResponse response = new RouteResponse();
        response.setOrigin(request.origin());
        response.setDestination(request.destination());
        response.setDistance(direction.distance());
        response.setDuration(direction.duration());
        response.setMode(direction.mode());
        response.setPolyline(direction.polyline());

        log.info("路线规划完成 | {}→{} | 距离={}m | 耗时={}s",
                request.origin(), request.destination(), direction.distance(), direction.duration());
        return response.toText();
    }

    /**
     * 距离计算：计算起点到多个目的地的距离并比较
     *
     * <p>完整流程：
     * 1. 地理编码起点
     * 2. 地理编码每个目的地
     * 3. 调用距离矩阵 API 计算各距离
     * 4. 格式化结果并推荐最近的目的地
     *
     * @param request 距离计算请求
     * @return 格式化的距离比较文本
     */
    public String calculateDistance(DistanceRequest request) {
        String origin = request.origin();
        List<String> destinations = request.destinations();

        log.info("距离计算 | origin={} | destinations={}", origin, destinations);

        // Step 1: 地理编码起点
        GeoPoint originPoint = mapClient.geocode(origin);

        // Step 2: 地理编码每个目的地
        List<GeoPoint> destPoints = new ArrayList<>();
        for (String dest : destinations) {
            try {
                destPoints.add(mapClient.geocode(dest));
            } catch (Exception e) {
                log.warn("目的地地理编码失败 | destination={}", dest);
                // 继续处理其他目的地
            }
        }

        if (destPoints.isEmpty()) {
            return "计算失败：无法获取目的地的坐标信息。";
        }

        // Step 3: 调用距离矩阵 API
        List<TencentMapClient.DistanceItem> distances;
        try {
            distances = mapClient.distanceMatrix(
                    originPoint.lat(), originPoint.lng(),
                    destPoints, "driving");
        } catch (TencentMapException e) {
            // 距离矩阵 API 失败时，逐个调用路线规划作为降级
            log.warn("距离矩阵 API 失败，降级为逐个路线规划");
            distances = new ArrayList<>();
            for (int i = 0; i < destPoints.size(); i++) {
                GeoPoint dp = destPoints.get(i);
                try {
                    DirectionResult dr = mapClient.direction(
                            originPoint.lat(), originPoint.lng(),
                            dp.lat(), dp.lng(), "driving");
                    distances.add(new TencentMapClient.DistanceItem(dr.distance(), dr.duration()));
                } catch (Exception ex) {
                    log.warn("路线规划降级失败 | destination={}", destinations.get(i));
                    distances.add(new TencentMapClient.DistanceItem(0, 0));
                }
            }
        }

        // Step 4: 格式化结果
        StringBuilder sb = new StringBuilder();
        sb.append("从").append(origin).append("出发：\n\n");

        int nearestIdx = -1;
        double nearestDist = Double.MAX_VALUE;

        for (int i = 0; i < destinations.size() && i < distances.size(); i++) {
            String dest = destinations.get(i);
            TencentMapClient.DistanceItem item = distances.get(i);

            if (item.distance() > 0) {
                sb.append(dest).append("：");
                double km = item.distance() / 1000.0;
                sb.append(String.format("%.0f公里", km));
                if (item.duration() > 0) {
                    sb.append("，").append(formatDuration(item.duration()));
                }
                sb.append("\n");

                if (item.distance() < nearestDist) {
                    nearestDist = item.distance();
                    nearestIdx = i;
                }
            } else {
                sb.append(dest).append("：距离计算失败\n");
            }
        }

        // 推荐最近的目的地
        if (nearestIdx >= 0 && nearestIdx < destinations.size()) {
            sb.append("\n推荐").append(destinations.get(nearestIdx)).append("，距离最近");
            double km = nearestDist / 1000.0;
            sb.append(String.format("（约%.0f公里）。", km));
        }

        log.info("距离计算完成 | destinations={}", destinations.size());
        return sb.toString().trim();
    }

    // ==================== Private helpers ====================

    private PlaceResponse toPlaceResponse(PoiResult poi) {
        PlaceResponse resp = new PlaceResponse();
        resp.setTitle(poi.title());
        resp.setAddress(poi.address());
        resp.setLatitude(poi.lat());
        resp.setLongitude(poi.lng());
        resp.setDistance(poi.distance());
        resp.setCategory(poi.category());
        return resp;
    }

    private static String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        if (hours > 0 && minutes > 0) {
            return hours + "小时" + minutes + "分钟";
        } else if (hours > 0) {
            return hours + "小时";
        } else {
            return minutes + "分钟";
        }
    }
}