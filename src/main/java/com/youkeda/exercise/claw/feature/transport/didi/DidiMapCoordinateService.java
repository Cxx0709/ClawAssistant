package com.youkeda.exercise.claw.feature.transport.didi;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 滴滴地址→坐标服务。
 *
 * <p>封装 {@code maps_textsearch} MCP 调用与坐标提取。滴滴要求坐标必须来自
 * maps_textsearch（而非地图编码器），业务层只需传地址名称，由本服务负责
 * 关键词搜索 → 从 POI 响应中提取经纬度。
 */
@Service
public class DidiMapCoordinateService {

    private static final Logger log = LoggerFactory.getLogger(DidiMapCoordinateService.class);

    private final DidiMcpClient mcpClient;

    public DidiMapCoordinateService(DidiMcpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * 调用 MCP maps_textsearch 获取地址坐标
     *
     * <p>maps_textsearch 需要 keywords（关键词）+ city（城市）两个必填参数。
     * 代码从查询词中自动提取城市名。
     */
    public JsonNode searchCoordinate(String query) {
        Map<String, Object> geoArgs = new LinkedHashMap<>();
        geoArgs.put("keywords", query);
        String city = extractCity(query);
        if (!city.isBlank()) {
            geoArgs.put("city", city);
        }
        return mcpClient.callToolWithTextResult("maps_textsearch", geoArgs);
    }

    /**
     * 从地址字符串中提取城市名
     *
     * <p>maps_textsearch 的 city 参数为必填，此方法尝试从地址前缀中提取城市名。
     * 例如："杭州余杭区阿里巴巴高桥云港" → "杭州"、"北京市天安门" → "北京市"。
     * 未匹配到已知城市时返回空字符串，不阻塞调用。
     */
    public String extractCity(String query) {
        if (query == null || query.isBlank()) return "";
        // 常见城市名列表（长名优先，避免"北京"误配"北京市"前缀给完整名）
        String[] cities = {
                "北京市", "上海市", "广州市", "深圳市", "杭州市", "成都市",
                "武汉市", "南京市", "重庆市", "天津市", "苏州市", "西安市",
                "长沙市", "郑州市", "东莞市", "青岛市", "沈阳市", "宁波市", "昆明市",
                "大连市", "厦门市", "合肥市", "佛山市", "福州市", "哈尔滨市", "济南市",
                "温州市", "长春市", "石家庄市", "常州市", "泉州市", "南宁市", "贵阳市",
                "南昌市", "太原市", "烟台市", "嘉兴市", "南通市", "金华市", "珠海市",
                "惠州市", "徐州市", "海口市", "乌鲁木齐市", "绍兴市", "中山市", "台州市",
                "兰州市", "北京", "上海", "广州", "深圳", "杭州", "成都",
                "武汉", "南京", "重庆", "天津", "苏州", "西安"
        };
        for (String city : cities) {
            if (query.startsWith(city)) {
                return city;
            }
        }
        return "";
    }

    /**
     * 从 maps_textsearch 响应中提取经度
     *
     * <p>支持以下响应格式：
     * <ul>
     *   <li>数组：取第一个元素，递归提取（maps_textsearch 默认返回 POI 列表）</li>
     *   <li>对象：直接匹配 lng/longitude/location.lng 等字段</li>
     * </ul>
     */
    public String extractLng(JsonNode geoResult) {
        // 处理数组：maps_textsearch 返回 [{location:{lng,lat}}, ...]
        if (geoResult.isArray() && geoResult.size() > 0) {
            return extractLng(geoResult.get(0));
        }
        // 尝试多种可能的字段路径
        if (geoResult.has("lng")) return geoResult.get("lng").asText("");
        if (geoResult.has("longitude")) return geoResult.get("longitude").asText("");
        if (geoResult.has("location")) {
            JsonNode loc = geoResult.get("location");
            if (loc.has("lng")) return loc.get("lng").asText("");
            if (loc.has("longitude")) return loc.get("longitude").asText("");
        }
        if (geoResult.has("result")) {
            JsonNode r = geoResult.get("result");
            if (r.has("location")) {
                JsonNode loc = r.get("location");
                if (loc.has("lng")) return loc.get("lng").asText("");
            }
        }
        log.warn("maps_textsearch 响应中未找到经度字段 | keys={}",
                joinFieldNames(geoResult));
        return "";
    }

    /**
     * 从 maps_textsearch 响应中提取纬度
     *
     * <p>支持以下响应格式：
     * <ul>
     *   <li>数组：取第一个元素，递归提取（maps_textsearch 默认返回 POI 列表）</li>
     *   <li>对象：直接匹配 lat/latitude/location.lat 等字段</li>
     * </ul>
     */
    public String extractLat(JsonNode geoResult) {
        // 处理数组：maps_textsearch 返回 [{location:{lng,lat}}, ...]
        if (geoResult.isArray() && geoResult.size() > 0) {
            return extractLat(geoResult.get(0));
        }
        if (geoResult.has("lat")) return geoResult.get("lat").asText("");
        if (geoResult.has("latitude")) return geoResult.get("latitude").asText("");
        if (geoResult.has("location")) {
            JsonNode loc = geoResult.get("location");
            if (loc.has("lat")) return loc.get("lat").asText("");
            if (loc.has("latitude")) return loc.get("latitude").asText("");
        }
        if (geoResult.has("result")) {
            JsonNode r = geoResult.get("result");
            if (r.has("location")) {
                JsonNode loc = r.get("location");
                if (loc.has("lat")) return loc.get("lat").asText("");
            }
        }
        log.warn("maps_textsearch 响应中未找到纬度字段 | keys={}",
                joinFieldNames(geoResult));
        return "";
    }

    /**
     * 拼接 JsonNode 的字段名为逗号分隔字符串
     */
    private static String joinFieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.toString();
    }
}
