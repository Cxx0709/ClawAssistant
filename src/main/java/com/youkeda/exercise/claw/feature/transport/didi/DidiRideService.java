package com.youkeda.exercise.claw.feature.transport.didi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.transport.didi.DidiRideStateStore.RideState;
import com.youkeda.exercise.claw.feature.transport.didi.DidiRideStateStore.RideStatus;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiEstimateRequest;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiEstimateResponse;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 滴滴打车业务编排层
 *
 * <p>职责：
 * <ul>
 *   <li>参数校验与转换</li>
 *   <li>调用 {@link DidiMcpClient#callTool} 执行 MCP 工具（taxi_estimate、taxi_create_order 等）</li>
 *   <li>通过 {@link DidiRideStateStore} 管理多轮状态</li>
 *   <li>地址→坐标委托 {@link DidiMapCoordinateService}，结果格式化委托 {@link DidiRideResultFormatter}</li>
 * </ul>
 *
 * <p>调用 {@code maps_textsearch} 获取坐标是 service 的内部职责，
 * LLM 只需传入地址名称，无需关心坐标获取细节。
 */
@Service
public class DidiRideService {

    private static final Logger log = LoggerFactory.getLogger(DidiRideService.class);

    private final DidiMcpClient mcpClient;
    private final DidiRideStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final DidiMapCoordinateService coordinateService;
    private final DidiRideResultFormatter formatter;

    public DidiRideService(DidiMcpClient mcpClient,
                           DidiRideStateStore stateStore,
                           ObjectMapper objectMapper,
                           DidiMapCoordinateService coordinateService,
                           DidiRideResultFormatter formatter) {
        this.mcpClient = mcpClient;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.coordinateService = coordinateService;
        this.formatter = formatter;
    }

    // ==================== 估价 ====================

    /**
     * 打车估价（MCP 调用链：maps_textsearch → taxi_estimate）
     *
     * <p>流程：
     * <ol>
     *   <li>解析起点/终点地址</li>
     *   <li>内部调用 {@code maps_textsearch} 获取滴滴认可的经纬度坐标</li>
     *   <li>调用 {@code taxi_estimate} 获取各车型估价和 traceId</li>
     *   <li>结果存入 {@link DidiRideStateStore}，状态设为 {@link RideStatus#ESTIMATED}</li>
     *   <li>返回格式化 JSON 给 LLM</li>
     * </ol>
     *
     * @param args   LLM 传入的参数（origin_name, destination_name 等）
     * @param userId 用户标识（状态隔离键）
     * @return 格式化估价结果
     */
    public String estimate(JsonNode args, String userId) {
        String originName = args.path("origin_name").asText("");
        String destinationName = args.path("destination_name").asText("");

        // 1. 参数校验
        if (originName.isBlank()) {
            return formatter.errorJson("缺少必填参数: origin_name（出发地名称）");
        }
        if (destinationName.isBlank()) {
            return formatter.errorJson("缺少必填参数: destination_name（目的地名称）");
        }

        log.info("打车估价 | from={} | to={}", originName, destinationName);

        try {
            // 2. 调用 maps_textsearch 获取起点坐标（滴滴 MCP 要求坐标必须来自 maps_textsearch）
            String fromLng = args.path("origin_lng").asText("");
            String fromLat = args.path("origin_lat").asText("");
            if (fromLng.isBlank() || fromLat.isBlank()) {
                log.info("调用 maps_textsearch 获取起点坐标 | query={}", originName);
                JsonNode originGeo = coordinateService.searchCoordinate(originName);
                fromLng = coordinateService.extractLng(originGeo);
                fromLat = coordinateService.extractLat(originGeo);
                log.info("起点坐标获取成功 | name={} | lng={} | lat={}", originName, fromLng, fromLat);
            }

            // 3. 调用 maps_textsearch 获取终点坐标
            String toLng = args.path("destination_lng").asText("");
            String toLat = args.path("destination_lat").asText("");
            if (toLng.isBlank() || toLat.isBlank()) {
                log.info("调用 maps_textsearch 获取终点坐标 | query={}", destinationName);
                JsonNode destGeo = coordinateService.searchCoordinate(destinationName);
                toLng = coordinateService.extractLng(destGeo);
                toLat = coordinateService.extractLat(destGeo);
                log.info("终点坐标获取成功 | name={} | lng={} | lat={}", destinationName, toLng, toLat);
            }

            // 4. 调用 taxi_estimate
            Map<String, Object> estimateArgs = new LinkedHashMap<>();
            estimateArgs.put("from_lng", fromLng);
            estimateArgs.put("from_lat", fromLat);
            estimateArgs.put("from_name", originName);
            estimateArgs.put("to_lng", toLng);
            estimateArgs.put("to_lat", toLat);
            estimateArgs.put("to_name", destinationName);

            log.info("调用 taxi_estimate | from={},{} | to={},{}", fromLng, fromLat, toLng, toLat);
            JsonNode estimateResult = mcpClient.callToolWithTextResult("taxi_estimate", estimateArgs);
            log.debug("taxi_estimate 响应 | result={}", estimateResult);

            // 5. 解析响应（支持 structuredContent 嵌套和扁平两种格式）
            JsonNode sc = estimateResult.get("structuredContent");
            JsonNode data = sc != null ? sc : estimateResult;

            String traceId = data.path("traceId").asText("");
            if (traceId.isBlank()) {
                log.warn("taxi_estimate 返回空 traceId | raw={}", estimateResult);
                return formatter.errorJson("估价失败：未获取到 traceId");
            }

            // 6. 构建响应模型
            TaxiEstimateRequest request = new TaxiEstimateRequest();
            request.setOriginName(originName);
            request.setOriginLng(fromLng);
            request.setOriginLat(fromLat);
            request.setDestName(destinationName);
            request.setDestLng(toLng);
            request.setDestLat(toLat);

            TaxiEstimateResponse response = formatter.parseEstimateResponse(traceId, data);

            // 7. 保存状态
            stateStore.saveEstimate(userId, request, response);

            // 8. 返回格式化 JSON
            return formatter.formatEstimateResult(response, traceId, originName, destinationName);

        } catch (DidiMcpException e) {
            log.error("打车估价失败 | error={}", e.getMessage());
            return formatter.errorJson("打车估价失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("打车估价异常 | error={}", e.getMessage(), e);
            return formatter.errorJson("打车估价异常：" + e.getMessage());
        }
    }

    // ==================== 创建订单 ====================

    /**
     * 创建打车订单。
     *
     * <p>必须先调用 {@link #estimate} 并获得用户确认。
     * 状态机检查：ESTIMATED → WAITING_CONFIRM（自动确认）→ 调用 MCP → ORDER_CREATED。
     *
     * @param args   LLM 传入的参数（product_category 等）
     * @param userId 用户标识（状态隔离键）
     * @return 订单创建结果
     */
    public String createOrder(JsonNode args, String userId) {
        // 1. 检查状态：必须有 estimate 记录
        RideState state;
        try {
            state = stateStore.getRequired(userId);
        } catch (IllegalStateException e) {
            return formatter.errorJson(e.getMessage());
        }

        // 2. 检查是否已创建
        if (state.status() == RideStatus.ORDER_CREATED) {
            return formatter.errorJson("订单已创建（orderId=" + state.orderId()
                    + "），请勿重复创建。如需重新叫车请先取消当前订单");
        }

        // 3. 状态路由
        if (state.status() == RideStatus.ESTIMATED) {
            // LLM 调用 create_order 即代表用户已在对话中确认
            // 自动完成 ESTIMATED → WAITING_CONFIRM 转型
            try {
                stateStore.confirmBooking(userId);
                state = stateStore.get(userId);
                log.info("用户确认打车 | traceId={}", state.traceId());
            } catch (IllegalStateException e) {
                return formatter.errorJson(e.getMessage());
            }
        }

        // WAITING_CONFIRM 检查：必须已经确认
        if (state.status() != RideStatus.WAITING_CONFIRM) {
            return formatter.errorJson("当前状态不允许创建订单：" + state.status()
                    + "。请先调用 estimate 并等待用户确认");
        }

        // 4. 提取参数
        String productCategory = args.path("product_category").asText("");
        if (productCategory.isBlank() && state.estimateResponse() != null) {
            // 容错：LLM 未传时取第一个车型
            productCategory = state.estimateResponse().getFirstProductCategory();
        }
        if (productCategory.isBlank()) {
            return formatter.errorJson("缺少必填参数: product_category（车型，如\"快车\"）");
        }

        String callerCarPhone = args.path("caller_car_phone").asText("");

        log.info("创建订单 | productCategory={} | traceId={}", productCategory, state.traceId());

        try {
            // 5. 调用 taxi_create_order
            Map<String, Object> orderArgs = new LinkedHashMap<>();
            orderArgs.put("product_category", productCategory);
            orderArgs.put("estimate_trace_id", state.traceId());
            if (!callerCarPhone.isBlank()) {
                orderArgs.put("caller_car_phone", callerCarPhone);
            }

            JsonNode orderResult = mcpClient.callToolWithTextResult("taxi_create_order", orderArgs);
            log.debug("taxi_create_order 响应 | result={}", orderResult);

            // 6. 解析响应
            JsonNode orderSc = orderResult.get("structuredContent");
            JsonNode orderData = orderSc != null ? orderSc : orderResult;

            String orderId = orderData.path("orderId").asText("");
            if (orderId.isBlank()) {
                log.warn("taxi_create_order 返回空 orderId | raw={}", orderResult);
                return formatter.errorJson("创建订单失败：未获取到订单号");
            }

            String status = orderData.path("status").asText("created");

            // 7. 保存订单
            stateStore.saveOrder(userId, orderId);

            // 8. 返回格式化结果
            TaxiOrderResponse response = new TaxiOrderResponse();
            response.setOrderId(orderId);
            response.setStatus(status);
            response.setFromName(state.estimateRequest() != null
                    ? state.estimateRequest().getOriginName() : "");
            response.setToName(state.estimateRequest() != null
                    ? state.estimateRequest().getDestName() : "");

            return formatter.formatOrderResult(response);

        } catch (DidiMcpException e) {
            log.error("创建订单失败 | error={}", e.getMessage());
            return formatter.errorJson("创建订单失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("创建订单异常 | error={}", e.getMessage(), e);
            return formatter.errorJson("创建订单异常：" + e.getMessage());
        }
    }

    // ==================== 查询订单 ====================

    /**
     * 查询订单状态
     */
    public String queryOrder(JsonNode args, String userId) {
        String orderId = resolveOrderId(args, userId);
        if (orderId == null) {
            return formatter.errorJson("缺少 order_id，且未找到进行中的订单");
        }

        log.info("查询订单 | orderId={}", orderId);

        try {
            Map<String, Object> queryArgs = new LinkedHashMap<>();
            queryArgs.put("order_id", orderId);

            JsonNode result = mcpClient.callToolWithTextResult("taxi_query_order", queryArgs);
            log.debug("taxi_query_order 响应 | result={}", result);

            JsonNode sc = result.get("structuredContent");
            JsonNode data = sc != null ? sc : result;

            return formatter.formatQueryResult(data, orderId);

        } catch (DidiMcpException e) {
            log.error("查询订单失败 | orderId={} | error={}",
                    orderId, e.getMessage());
            return formatter.errorJson("查询订单失败：" + e.getMessage());
        }
    }

    // ==================== 取消订单 ====================

    /**
     * 取消订单
     */
    public String cancelOrder(JsonNode args, String userId) {
        String orderId = resolveOrderId(args, userId);
        if (orderId == null) {
            return formatter.errorJson("缺少 order_id，且未找到进行中的订单");
        }

        log.info("取消订单 | orderId={}", orderId);

        try {
            Map<String, Object> cancelArgs = new LinkedHashMap<>();
            cancelArgs.put("order_id", orderId);

            JsonNode result = mcpClient.callToolWithTextResult("taxi_cancel_order", cancelArgs);
            log.debug("taxi_cancel_order 响应 | result={}", result);

            // 清除状态
            stateStore.clear(userId);

            ObjectNode output = objectMapper.createObjectNode();
            output.put("status", "cancelled");
            output.put("order_id", orderId);
            output.put("message", "订单 " + orderId + " 已取消");
            return objectMapper.writeValueAsString(output);

        } catch (Exception e) {
            log.error("取消订单失败 | orderId={} | error={}",
                    orderId, e.getMessage());
            return formatter.errorJson("取消订单失败：" + e.getMessage());
        }
    }

    // ==================== 生成跳转链接 ====================

    /**
     * 生成跳转滴滴 App/小程序的深度链接
     *
     * <p>与 estimate 一样，需要先通过 maps_textsearch 获取坐标，
     * 因为 taxi_generate_ride_app_link 要求传入经纬度坐标。
     */
    public String generateLink(JsonNode args, String userId) {
        String originName = args.path("origin_name").asText("");
        String destinationName = args.path("destination_name").asText("");

        if (originName.isBlank() || destinationName.isBlank()) {
            return formatter.errorJson("生成跳转链接需要 origin_name 和 destination_name");
        }

        log.info("生成跳转链接 | from={} | to={}", originName, destinationName);

        try {
            // 1. 获取起点坐标
            log.info("调用 maps_textsearch 获取起点坐标 | query={}", originName);
            JsonNode originGeo = coordinateService.searchCoordinate(originName);
            String fromLng = coordinateService.extractLng(originGeo);
            String fromLat = coordinateService.extractLat(originGeo);

            // 2. 获取终点坐标
            log.info("调用 maps_textsearch 获取终点坐标 | query={}", destinationName);
            JsonNode destGeo = coordinateService.searchCoordinate(destinationName);
            String toLng = coordinateService.extractLng(destGeo);
            String toLat = coordinateService.extractLat(destGeo);

            // 3. 调用 taxi_generate_ride_app_link（携带坐标）
            Map<String, Object> linkArgs = new LinkedHashMap<>();
            linkArgs.put("from_name", originName);
            linkArgs.put("from_lng", fromLng);
            linkArgs.put("from_lat", fromLat);
            linkArgs.put("to_name", destinationName);
            linkArgs.put("to_lng", toLng);
            linkArgs.put("to_lat", toLat);

            JsonNode result = mcpClient.callToolWithTextResult("taxi_generate_ride_app_link", linkArgs);
            log.debug("taxi_generate_ride_app_link 响应 | result={}", result);

            ObjectNode output = objectMapper.createObjectNode();
            output.put("status", "success");
            output.put("link_type", "didi_app_link");
            output.put("origin_name", originName);
            output.put("destination_name", destinationName);
            output.set("data", result);
            return objectMapper.writeValueAsString(output);

        } catch (Exception e) {
            log.warn("生成跳转链接失败 | error={}", e.getMessage());
            return formatter.errorJson("生成跳转链接失败：" + e.getMessage()
                    + "。可提示用户自行打开滴滴 App 叫车");
        }
    }

    /**
     * 解析 order_id 参数：优先从 args 中取，其次从 StateStore 中恢复
     */
    private String resolveOrderId(JsonNode args, String userId) {
        // 优先 LLM 传入
        String orderId = args.path("order_id").asText("");
        if (!orderId.isBlank()) {
            return orderId;
        }

        // 从状态存储中恢复
        RideState state = stateStore.get(userId);
        if (state != null && state.orderId() != null) {
            return state.orderId();
        }

        return null;
    }
}
