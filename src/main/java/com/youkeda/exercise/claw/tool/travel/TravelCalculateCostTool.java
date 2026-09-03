package com.youkeda.exercise.claw.tool.travel;
import com.youkeda.exercise.claw.feature.travel.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.budget.BatchPlanCostRequest;
import com.youkeda.exercise.claw.feature.budget.BudgetCalculatorService;
import org.springframework.stereotype.Component;

/** 将旅游方案成本核算注册为可供 LLM 调用的独立工具。 */
@Component
public class TravelCalculateCostTool extends AbstractTool {

    private final BudgetCalculatorService calculatorService;

    public TravelCalculateCostTool(BudgetCalculatorService calculatorService,
                                         ObjectMapper objectMapper,
                                         ToolRegistry registry) {
        super(registry, objectMapper);
        this.calculatorService = calculatorService;
    }

    @Override
    public String getName() {
        return "travel_calculate_cost";
    }

    @Override
    public String getDescription() {
        return "根据旅游方案的具体费用项目、计费方式、数量和单价，"
                + "核算方案预计总费用、人均费用、分类明细和预算差额。"
                + "支持按人数、天数自动计算房间数（向上取整）、车辆数（向上取整）、桌数。"
                + "本工具不按固定行业比例分配用户预算，也不生成或猜测单价。"
                + "价格缺失时必须传 price_status=MISSING，工具会返回 PARTIAL 状态及缺失项列表。"
                + "完整旅游方案中的所有乘法、房间/车辆取整和费用汇总必须使用本工具。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("headcount", "参加人数，正整数", true)
                .integer("days", "出行天数，正整数", true)
                .integer("nights", "住宿晚数；未提供时默认 days-1", false)
                .string("city", "目的地城市，仅用于方案上下文，不使用虚构城市系数", false)
                .number("target_total_budget", "用户总预算上限；与人均预算至少提供一个，仅用于超支比较", false)
                .number("target_per_person_budget", "用户人均预算上限；与总预算至少提供一个，仅用于超支比较", false)
                .number("contingency_rate", "可选：机动费用比例，0到1，默认0.10", false)
                .array("plans", "待核算的一个或多个候选方案", true)
                    .string("plan_id", "稳定的内部方案标识", true)
                    .string("plan_name", "面向用户的方案名称", true)
                    .integer("plan_version", "方案版本，默认1", false)
                    .array("items", "该方案的费用项目", true)
                        .string("category", "TRANSPORT、ACCOMMODATION、MEAL、TICKET、ACTIVITY、INSURANCE、MATERIAL、VENUE、SERVICE 或 OTHER", true)
                        .string("item_name", "具体费用项目名称", true)
                        .string("pricing_mode", "FIXED、PER_PERSON、PER_PERSON_PER_DAY、PER_PERSON_PER_OCCURRENCE、PER_ROOM_PER_NIGHT、PER_VEHICLE、PER_TABLE 或 PER_UNIT", true)
                        .number("unit_price", "确定单价；有价格区间时不填写", false)
                        .number("min_unit_price", "最低参考单价", false)
                        .number("max_unit_price", "最高参考单价", false)
                        .number("quantity", "明确数量；PER_UNIT 必填，也可覆盖车辆、房间或桌数的自动计算", false)
                        .integer("occurrences", "餐次、活动次数等", false)
                        .integer("capacity", "每辆车、每间房或每桌容纳人数，用于向上取整", false)
                        .integer("applicable_headcount", "该项目实际计费人数；未提供时使用总人数", false)
                        .string("price_source", "官方、商家、专业工具或网页来源", false)
                        .string("price_status", "CONFIRMED、ESTIMATED 或 MISSING", true)
                        .string("notes", "价格假设和补充说明", false)
                        .end()
                    .end()
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            BatchPlanCostRequest request = objectMapper.readValue(argumentsJson, BatchPlanCostRequest.class);
            return objectMapper.writeValueAsString(calculatorService.calculate(request));
        } catch (Exception e) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "INVALID_ARGUMENT");
            result.putArray("warnings").add("成本核算参数解析失败：" + e.getMessage());
            return result.toString();
        }
    }
}
