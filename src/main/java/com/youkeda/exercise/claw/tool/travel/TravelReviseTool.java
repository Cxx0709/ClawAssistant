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
 * 修订旅游方案工具。
 *
 * <p>当用户对已有方案不满意、需要修改、组合方案或指定修订某个方案时调用。
 * 支持通用修订（反馈原话）、指定方案修订、多方案组合三种模式。
 */
@Component
public class TravelReviseTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TravelReviseTool.class);

    private final TravelPlanService planService;

    public TravelReviseTool(TravelPlanService planService,
                                  ObjectMapper objectMapper,
                                  ToolRegistry registry) {
        super(registry, objectMapper);
        this.planService = planService;
    }

    @Override
    public String getName() {
        return "travel_revise";
    }

    @Override
    public String getDescription() {
        return "根据用户反馈修订旅游方案。"
                + "用户对已有方案不满意、要求修改、组合方案或指定调整某个方案时调用。"
                + "传入 source_option_ids 时为组合模式——从多个源方案生成一个新方案；"
                + "传入 option_id 加其他修改字段时为指定方案修订模式；"
                + "只传入 feedback 时为通用修订模式。"
                + "修订后旧成本失效，需重新调用 budget_calculator 核算。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("feedback", "用户对旧方案的不满意或修改意见原文；通用修订时使用", false)
                .string("option_id", "需要修改的候选方案标识；指定方案修订时使用", false)
                .string("display_name", "修订后的方案名称", false)
                .string("positioning", "修订后的方案定位", false)
                .string("highlights", "修订后的方案亮点", false)
                .string("itinerary_summary", "修订后的行程概要", false)
                .arrayOfScalar("source_option_ids", "组合模式——被组合的源方案标识列表，至少2个", "string", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            ObjectNode args = (ObjectNode) objectMapper.readTree(argumentsJson);

            // 根据参数判断路由
            JsonNode sourceIds = args.get("source_option_ids");
            if (sourceIds != null && sourceIds.isArray() && sourceIds.size() >= 2) {
                args.put("action", "combine_options");
            } else if (args.has("option_id") && !args.get("option_id").asText().isBlank()
                    && (args.has("display_name") || hasAny(args, "positioning", "highlights", "itinerary_summary"))) {
                args.put("action", "revise_option");
            } else {
                args.put("action", "revise");
            }

            return objectMapper.writeValueAsString(planService.handle(args));
        } catch (Exception e) {
            log.error("travel_revise 执行失败 | error={}", e.getMessage());
            return error("修订方案失败: " + e.getMessage());
        }
    }

    private boolean hasAny(ObjectNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return false;
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message);
        return node.toString();
    }
}
