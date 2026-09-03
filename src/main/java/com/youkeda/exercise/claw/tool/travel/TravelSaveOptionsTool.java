package com.youkeda.exercise.claw.tool.travel;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.travel.TravelPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 保存旅游候选方案工具。
 *
 * <p>当一个或多个候选方案已成形，需要进入比较和选择阶段时调用。
 * 方案必须有明确标识、名称、定位和行程概要。
 */
@Component
public class TravelSaveOptionsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TravelSaveOptionsTool.class);

    private final TravelPlanService planService;

    public TravelSaveOptionsTool(TravelPlanService planService,
                                       ObjectMapper objectMapper,
                                       ToolRegistry registry) {
        super(registry, objectMapper);
        this.planService = planService;
    }

    @Override
    public String getName() {
        return "travel_save_options";
    }

    @Override
    public String getDescription() {
        return "保存已生成的候选旅游方案。"
                + "当已有一个或多个完整的差异化方案，需要进入比较和选择阶段时调用。"
                + "用户未指定数量时默认生成3个方案，明确指定时按指定数量生成，最多5个。"
                + "调用前应先生成各方案的行程和费用项目，调用后再用 budget_calculator 核算总费用。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .integer("option_count", "候选方案数量，必须与当前方案数量一致；用户未指定时默认3，明确指定时最多5", false)
                .array("options", "待保存的候选方案列表，至少1个，最多5个", true)
                    .string("option_id", "稳定的内部方案标识，如 plan_a、plan_b", true)
                    .string("display_name", "面向用户的方案名称，如方案A、方案B", true)
                    .string("positioning", "方案定位：经济型、均衡型、体验型等", true)
                    .string("highlights", "方案主要亮点，区别于其他方案的核心特色", false)
                    .string("itinerary_summary", "方案行程概要，概述每天的主要活动和安排", true)
                    .end()
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            ObjectNode args = (ObjectNode) objectMapper.readTree(argumentsJson);
            return objectMapper.writeValueAsString(planService.handle(args));
        } catch (Exception e) {
            log.error("travel_save_options 执行失败 | error={}", e.getMessage());
            return error("保存候选方案失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message);
        return node.toString();
    }
}
