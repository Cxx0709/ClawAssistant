package com.youkeda.exercise.claw.feature.transport.didi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiEstimateResponse;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 滴滴打车结果格式化服务。
 *
 * <p>把 MCP 响应解析/格式化为 LLM 可读的结构化 JSON，
 * 并统一构建错误 JSON。纯函数式，无状态。
 */
@Service
public class DidiRideResultFormatter {

    private static final Logger log = LoggerFactory.getLogger(DidiRideResultFormatter.class);

    private final ObjectMapper objectMapper;

    public DidiRideResultFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 taxi_estimate 响应中的 items 列表
     */
    public TaxiEstimateResponse parseEstimateResponse(String traceId, JsonNode data) {
        TaxiEstimateResponse response = new TaxiEstimateResponse();
        response.setTraceId(traceId);

        List<TaxiEstimateResponse.EstimateItem> items = new ArrayList<>();
        JsonNode itemsNode = data.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                TaxiEstimateResponse.EstimateItem ei = new TaxiEstimateResponse.EstimateItem();
                ei.setProductName(item.path("productName").asText(""));
                ei.setProductCategory(item.path("productCategory").asText(""));
                ei.setPriceText(item.path("priceText").asText(""));
                ei.setDestTimeText(item.path("destTimeText").asText(""));
                items.add(ei);
            }
        }
        response.setItems(items);

        return response;
    }

    /**
     * 格式化估价结果为 LLM 可读的 JSON
     */
    public String formatEstimateResult(TaxiEstimateResponse response, String traceId,
                                       String originName, String destinationName) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", "estimate_completed");
            root.put("origin_name", originName);
            root.put("destination_name", destinationName);

            ArrayNode products = root.putArray("available_products");
            for (TaxiEstimateResponse.EstimateItem item : response.getItems()) {
                ObjectNode p = products.addObject();
                p.put("product_name", item.getProductName());
                p.put("price", item.getPriceText() + "元");
                if (item.getDestTimeText() != null && !item.getDestTimeText().isBlank()) {
                    p.put("estimated_time", item.getDestTimeText());
                }
                p.put("product_category", item.getProductCategory());
            }

            root.put("message", "已获取打车估价，请将以上价格展示给用户并获得确认后，再调用 create_order");
            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("格式化估价结果失败", e);
            return "{\"status\":\"estimate_completed\",\"traceId\":\"" + traceId + "\"}";
        }
    }

    /**
     * 格式化订单创建结果为 LLM 可读的 JSON
     */
    public String formatOrderResult(TaxiOrderResponse response) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", "order_created");
            root.put("order_id", response.getOrderId());
            root.put("order_status", response.getStatus());
            root.put("from", response.getFromName());
            root.put("to", response.getToName());
            root.put("message", "订单已创建，请告知用户订单号和预计等待时间");
            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("格式化订单结果失败", e);
            return "{\"status\":\"order_created\",\"orderId\":\"" + response.getOrderId() + "\"}";
        }
    }

    /**
     * 格式化查询订单结果
     */
    public String formatQueryResult(JsonNode data, String orderId) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("order_id", orderId);
            root.put("status", data.path("status").asText("unknown"));
            root.put("driver_name", data.path("driverName").asText(""));
            root.put("driver_phone", data.path("driverPhone").asText(""));
            root.put("car_number", data.path("carNumber").asText(""));

            // 司机位置（如 MCP 提供）
            JsonNode driverLoc = data.get("driverLocation");
            if (driverLoc != null) {
                root.set("driver_location", driverLoc);
            }

            // 行程进度
            if (data.has("progress")) {
                root.put("progress", data.path("progress").asText(""));
            }

            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("格式化查询结果失败", e);
            return "{\"order_id\":\"" + orderId + "\",\"status\":\"queried\"}";
        }
    }

    /**
     * 构建错误 JSON
     */
    public String errorJson(String message) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", "error");
            root.put("error", message);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
