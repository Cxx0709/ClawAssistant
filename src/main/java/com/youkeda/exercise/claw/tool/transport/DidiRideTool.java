package com.youkeda.exercise.claw.tool.transport;
import com.youkeda.exercise.claw.feature.transport.didi.DidiRideService;
import com.youkeda.exercise.claw.feature.transport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 滴滴打车 LLM Function
 *
 * <p>将滴滴 MCP 打车能力以 LLM Function Calling 的方式暴露给 ReActAgentExecutor。
 * 注册函数名 {@code didi_ride}，是一个统一的滴滴领域入口。
 *
 * <p>支持的 action：
 * <ul>
 *   <li><b>estimate</b> — 查询打车费用。根据起点/终点地址获取各车型估价。</li>
 *   <li><b>create_order</b> — 创建订单。必须先完成 estimate 并获得用户确认。</li>
 *   <li><b>query_order</b> — 查询订单状态/司机信息。</li>
 *   <li><b>cancel_order</b> — 取消订单。</li>
 *   <li><b>generate_link</b> — 生成跳转滴滴 App/小程序的深度链接。</li>
 * </ul>
 *
 * <p>核心调用链：
 * <pre>
 * LLM → DidiRideTool → DidiRideService → DidiMcpClient → 滴滴 MCP Server
 * </pre>
 */
@Component
public class DidiRideTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(DidiRideTool.class);

    private final DidiRideService rideService;

    public DidiRideTool(DidiRideService rideService,
                            ObjectMapper objectMapper,
                            ToolRegistry functionRegistry) {
        super(functionRegistry, objectMapper);
        this.rideService = rideService;
    }

    @Override
    public String getName() {
        return "didi_ride";
    }

    @Override
    public String getDescription() {
        return "滴滴打车能力。当用户选择打车方式前往车站、机场、景点等目的地时调用。\n"
                + "支持以下操作：\n"
                + "1. estimate — 查询打车费用。根据起点和目的地地址获取各车型（快车、优享、专车等）的预估价格和时间。\n"
                + "2. create_order — 创建订单。注意：创建订单前必须先调用 estimate，"
                + "并将估价结果展示给用户，获得用户明确确认后才能创建订单。不得在用户确认前自动创建。\n"
                + "3. query_order — 查询订单状态和司机信息。\n"
                + "4. cancel_order — 取消已有订单。\n"
                + "5. generate_link — 生成跳转滴滴 App/小程序的链接。\n"
                + "坐标由系统自动处理，传入地址名称即可，用户无需提供经纬度。\n"
                + "重要：每次新打车必须使用用户当次明确提到的出发地和目的地，"
                + "不得沿用对话中历史行程的地址；用户未说明时主动询问，而不是复用上次的地址。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：estimate（估价）、create_order（创建订单）、"
                + "query_order（查询订单）、cancel_order（取消订单）、generate_link（生成跳转链接）");
        action.putArray("enum")
                .add("estimate").add("create_order")
                .add("query_order").add("cancel_order")
                .add("generate_link");

        return schema()
                .raw("action", action, true)
                .string("origin_name", "出发地名称，如：北京南站、天安门广场、我的当前位置。estimate 和 generate_link 时使用", false)
                .string("destination_name", "目的地名称，如：首都国际机场、西湖。estimate 和 generate_link 时使用", false)
                .string("product_category", "车型标识，来自 estimate 返回的 product_category。"
                        + "如：快车、优享、专车、豪华车。create_order 时使用（如不传则默认第一项）", false)
                .string("order_id", "订单 ID。query_order 和 cancel_order 时使用，不传则自动使用最近订单", false)
                .string("caller_car_phone", "叫车人手机号（可选），create_order 时使用", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String action = args.path("action").asText("");
            String userId = context.userId();

            if (action.isBlank()) {
                return "{\"status\":\"error\",\"error\":\"缺少必填参数: action（操作类型）\"}";
            }
            if (userId == null || userId.isBlank()) {
                return "{\"status\":\"error\",\"error\":\"缺少用户ID\"}";
            }

            log.info("DidiRideTool 执行 | action={} | userId={} | args={}", action, userId, args);

            return switch (action) {
                case "estimate" -> rideService.estimate(args, userId);
                case "create_order" -> rideService.createOrder(args, userId);
                case "query_order" -> rideService.queryOrder(args, userId);
                case "cancel_order" -> rideService.cancelOrder(args, userId);
                case "generate_link" -> rideService.generateLink(args, userId);
                default ->
                    "{\"status\":\"error\",\"error\":\"不支持的 action: " + action
                            + "，支持的 action: estimate, create_order, query_order, cancel_order, generate_link\"}";
            };

        } catch (Exception e) {
            log.error("DidiRideTool 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            return "{\"status\":\"error\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
