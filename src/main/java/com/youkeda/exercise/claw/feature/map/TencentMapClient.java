package com.youkeda.exercise.claw.feature.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.infrastructure.common.HttpClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯地图 HTTP API 封装
 *
 * <p>封装腾讯位置服务（lbs.qq.com）的 REST API 调用。
 * 包含地理编码、地点搜索、路线规划等能力。
 * 上层 {@link MapService} 调用此类，禁止 LLM Function 直接调用 HTTP。
 */
@Component
public class TencentMapClient {

    private static final Logger log = LoggerFactory.getLogger(TencentMapClient.class);

    /** 地理编码 API */
    private static final String GEOCODING_URL = "https://apis.map.qq.com/ws/geocoder/v1/";

    /** 地点搜索 API */
    private static final String SEARCH_URL = "https://apis.map.qq.com/ws/place/v1/search";

    /** 路线规划 API 模板（{mode} 替换为 driving/walking/transit） */
    private static final String DIRECTION_URL_TEMPLATE = "https://apis.map.qq.com/ws/direction/v1/%s/";

    /** 距离矩阵 API */
    private static final String DISTANCE_URL = "https://apis.map.qq.com/ws/distance/v1/";

    private final TencentMapProperties properties;
    private final HttpClientUtil httpClient;
    private final ObjectMapper objectMapper;

    public TencentMapClient(TencentMapProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = new HttpClientUtil();
    }

    /**
     * 地理编码：将地址描述转换为经纬度坐标
     *
     * @param address 地址描述，如"无锡学院"
     * @return GeoPoint 经纬度坐标
     * @throws TencentMapException 地理编码失败时抛出
     */
    public GeoPoint geocode(String address) throws TencentMapException {
        checkApiKey();
        try {
            String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = GEOCODING_URL + "?address=" + encodedAddress + "&key=" + properties.getApiKey();

            log.debug("地理编码请求 | address={}", address);

            String responseBody = httpClient.doGet(url);
            JsonNode root = objectMapper.readTree(responseBody);

            // 348 = 参数错误，通常是地名不是标准行政区划（如"莫干山""拈花湾"等景区名）
            if (root.path("status").asInt(-1) == 348) {
                throw new TencentMapException("348",
                        "无法识别地址「" + address + "」，该地址不是标准行政区划名称。" +
                        "请尝试使用城市或区县名称，例如「湖州德清」「无锡拈花湾」");
            }

            checkApiResponse(root, "地理编码");

            JsonNode location = root.path("result").path("location");
            double lat = location.get("lat").asDouble();
            double lng = location.get("lng").asDouble();

            log.info("地理编码成功 | address={} | lat={} | lng={}", address, lat, lng);
            return new GeoPoint(lat, lng);

        } catch (TencentMapException e) {
            throw e;
        } catch (Exception e) {
            log.error("地理编码失败 | address={} | error={}", address, e.getMessage());
            throw new TencentMapException("地理编码失败: " + address, e);
        }
    }

    /**
     * 地点搜索：根据关键词和城市搜索 POI
     *
     * @param keyword 搜索关键词，如"团建基地"
     * @param city    城市名称，如"无锡"
     * @return POI 结果列表
     * @throws TencentMapException 搜索失败时抛出
     */
    public List<PoiResult> searchPoi(String keyword, String city) throws TencentMapException {
        checkApiKey();
        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            // 使用 region 边界限定搜索范围（city 原样放入 region，不额外编码）
            // region(city,1) 中第二个参数 1 表示自动补全区域
            String boundary = city != null && !city.isBlank()
                    ? "region(" + city + ",1)"
                    : "nearby(39.9087,116.3975,50000)"; // 无城市时默认北京附近

            String url = SEARCH_URL + "?keyword=" + encodedKeyword
                    + "&boundary=" + URLEncoder.encode(boundary, StandardCharsets.UTF_8)
                    + "&page_size=5"
                    + "&key=" + properties.getApiKey()
                    + "&orderby=distance";

            log.debug("地点搜索请求 | keyword={} | city={}", keyword, city);

            String responseBody = httpClient.doGet(url);
            JsonNode root = objectMapper.readTree(responseBody);

            checkApiResponse(root, "地点搜索");

            List<PoiResult> results = new ArrayList<>();
            JsonNode dataArray = root.path("data");
            if (dataArray.isArray()) {
                for (JsonNode item : dataArray) {
                    String title = item.path("title").asText("");
                    String address = item.path("address").asText("");
                    JsonNode loc = item.path("location");
                    double lat = loc.path("lat").asDouble(0);
                    double lng = loc.path("lng").asDouble(0);
                    double distance = item.path("_distance").asDouble(0);
                    String category = item.path("category").asText("");

                    results.add(new PoiResult(title, address, lat, lng, distance, category));
                }
            }

            log.info("地点搜索成功 | keyword={} | city={} | 结果数={}", keyword, city, results.size());
            return results;

        } catch (TencentMapException e) {
            throw e;
        } catch (Exception e) {
            log.error("地点搜索失败 | keyword={} | city={} | error={}", keyword, city, e.getMessage());
            throw new TencentMapException("地点搜索失败: " + keyword, e);
        }
    }

    /**
     * 路线规划：查询两点之间的驾车/步行/公交路线
     *
     * @param fromLat  起点纬度
     * @param fromLng  起点经度
     * @param toLat    终点纬度
     * @param toLng    终点经度
     * @param mode     出行方式：driving / walking / transit
     * @return 路线规划结果
     * @throws TencentMapException 路线规划失败时抛出
     */
    public DirectionResult direction(double fromLat, double fromLng,
                                     double toLat, double toLng,
                                     String mode) throws TencentMapException {
        checkApiKey();
        try {
            String normalizedMode = normalizeMode(mode);
            String directionUrl = String.format(DIRECTION_URL_TEMPLATE, normalizedMode);
            String from = fromLat + "," + fromLng;
            String to = toLat + "," + toLng;

            String url = directionUrl + "?from=" + from + "&to=" + to + "&key=" + properties.getApiKey();

            log.debug("路线规划请求 | from={} | to={} | mode={}", from, to, normalizedMode);

            String responseBody = httpClient.doGet(url);
            JsonNode root = objectMapper.readTree(responseBody);

            checkApiResponse(root, "路线规划");

            JsonNode routes = root.path("result").path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                throw new TencentMapException("ROUTE_EMPTY", "未找到可用路线");
            }

            // 取第一条路线（最优路线）
            JsonNode bestRoute = routes.get(0);
            double distance = bestRoute.path("distance").asDouble(0);
            int duration = bestRoute.path("duration").asInt(0);

            // 提取路线概览（steps 中的道路名称）
            StringBuilder polyline = new StringBuilder();
            JsonNode steps = bestRoute.path("steps");
            if (steps.isArray()) {
                for (int i = 0; i < Math.min(steps.size(), 5); i++) {
                    String roadName = steps.get(i).path("road_name").asText("");
                    if (!roadName.isBlank()) {
                        if (polyline.length() > 0) polyline.append(" → ");
                        polyline.append(roadName);
                    }
                }
            }

            log.info("路线规划成功 | distance={}m | duration={}s | mode={}", distance, duration, normalizedMode);
            return new DirectionResult(distance, duration, normalizedMode, polyline.toString());

        } catch (TencentMapException e) {
            throw e;
        } catch (Exception e) {
            log.error("路线规划失败 | from={},{} | to={},{} | error={}",
                    fromLat, fromLng, toLat, toLng, e.getMessage());
            throw new TencentMapException("路线规划失败", e);
        }
    }

    /**
     * 距离矩阵：计算从起点到多个终点的驾车距离
     *
     * @param fromLat 起点纬度
     * @param fromLng 起点经度
     * @param toPoints 终点坐标列表
     * @return 各终点距离信息
     * @throws TencentMapException 计算失败时抛出
     */
    public List<DistanceItem> distanceMatrix(double fromLat, double fromLng,
                                             List<GeoPoint> toPoints,
                                             String mode) throws TencentMapException {
        checkApiKey();
        try {
            String normalizedMode = normalizeMode(mode);
            String from = fromLat + "," + fromLng;

            // 构建多个终点参数：to=lat1,lng1;lat2,lng2
            StringBuilder toBuilder = new StringBuilder();
            for (GeoPoint point : toPoints) {
                if (toBuilder.length() > 0) toBuilder.append(";");
                toBuilder.append(point.lat()).append(",").append(point.lng());
            }
            String to = toBuilder.toString();

            String url = DISTANCE_URL + "?mode=" + normalizedMode
                    + "&from=" + from
                    + "&to=" + to
                    + "&key=" + properties.getApiKey();

            log.debug("距离矩阵请求 | from={} | destinations={}", from, toPoints.size());

            String responseBody = httpClient.doGet(url);
            JsonNode root = objectMapper.readTree(responseBody);

            checkApiResponse(root, "距离矩阵");

            List<DistanceItem> results = new ArrayList<>();
            JsonNode elements = root.path("result").path("elements");
            if (elements.isArray()) {
                for (int i = 0; i < elements.size(); i++) {
                    JsonNode elem = elements.get(i);
                    double dist = elem.path("distance").asDouble(0);
                    int dur = elem.path("duration").asInt(0);
                    results.add(new DistanceItem(dist, dur));
                }
            }

            log.info("距离矩阵计算成功 | results={}", results.size());
            return results;

        } catch (TencentMapException e) {
            throw e;
        } catch (Exception e) {
            log.error("距离矩阵计算失败 | error={}", e.getMessage());
            throw new TencentMapException("距离计算失败", e);
        }
    }

    // ==================== Internal helpers ====================

    private void checkApiKey() {
        String key = properties.getApiKey();
        if (key == null || key.isEmpty() || "YOUR_API_KEY_HERE".equals(key)) {
            throw new TencentMapException("API_KEY_MISSING", "腾讯地图 API Key 未配置");
        }
    }

    /**
     * 检查腾讯地图 API 返回的 status 字段
     */
    private void checkApiResponse(JsonNode root, String apiName) throws TencentMapException {
        int status = root.path("status").asInt(-1);
        if (status != 0) {
            String message = root.path("message").asText("未知错误");
            throw new TencentMapException(String.valueOf(status),
                    apiName + " API 返回错误: " + message);
        }
    }

    /**
     * 标准化出行方式
     */
    private static String normalizeMode(String mode) {
        if (mode == null) return "driving";
        return switch (mode.trim().toLowerCase()) {
            case "walking", "步行" -> "walking";
            case "transit", "公交", "地铁" -> "transit";
            default -> "driving";
        };
    }

    // ==================== Internal data classes ====================

    /**
     * 经纬度坐标
     */
    public record GeoPoint(double lat, double lng) {
    }

    /**
     * POI 搜索结果
     */
    public record PoiResult(String title, String address,
                            double lat, double lng,
                            double distance, String category) {
    }

    /**
     * 路线规划结果
     */
    public record DirectionResult(double distance, int duration,
                                  String mode, String polyline) {
    }

    /**
     * 距离矩阵单项结果
     */
    public record DistanceItem(double distance, int duration) {
    }
}