package com.youkeda.exercise.claw.tool.travel;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.travel.TravelPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 旅游需求收集工具。
 *
 * <p>当用户需要制定旅游/出游方案且缺少关键信息（出发地、人数、日期、天数、目的地、预算）时调用。
 * 信息不足时返回 NEED_MORE_INFORMATION 和具体缺失字段，LLM 应据此追问。
 */
@Component
public class TravelCollectTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TravelCollectTool.class);

    private final TravelPlanService planService;

    public TravelCollectTool(TravelPlanService planService,
                                   ObjectMapper objectMapper,
                                   ToolRegistry registry) {
        super(registry, objectMapper);
        this.planService = planService;
    }

    @Override
    public String getName() {
        return "travel_collect";
    }

    @Override
    public String getDescription() {
        return "收集和更新旅游规划需求。"
                + "当用户需要制定旅游、公司出游、部门活动、集体旅行或完整多人行程方案时调用。"
                + "传入用户已提供的信息（出发地、人数、日期、天数、目的地/范围、预算等）；"
                + "必要字段缺失时返回 NEED_MORE_INFORMATION 和缺失字段列表，LLM 应逐一追问。"
                + "新方案首次调用前，若明显缺少必填信息（缺3项以上），应先用文字一次性追问，不调用此工具。"
                + "已有方案状态时，用此工具记录用户补充或修改的信息。"
                + "普通景点问答和简单地点推荐不调用。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("departure_city", "出发城市或集合地点", false)
                .integer("participant_count", "参加人数，正整数", false)
                .string("travel_date", "出行日期或时间范围", false)
                .string("duration", "出行时长，如2天1晚", false)
                .integer("days", "标准化出行天数", false)
                .integer("nights", "住宿晚数", false)
                .integer("option_count", "候选方案数量；用户未指定时默认3个，明确指定时最多5个", false)
                .number("budget_total", "用户可接受的团队总预算上限，单位元；与人均预算至少提供一个", false)
                .number("budget_per_person", "用户可接受的人均预算上限，单位元；与总预算至少提供一个", false)
                .string("budget_level", "可选：经济型、标准型或品质型偏好，不能代替数值预算", false)
                .number("max_overrun_amount", "可选：用户提前允许的最大超预算金额", false)
                .number("max_overrun_rate", "可选：用户提前允许的最大超预算比例，百分数", false)
                .string("destination", "确定的目的地", false)
                .string("travel_scope", "目的地未定时可接受的范围", false)
                .string("team_goal", "出行目标", false)
                .string("activity_preferences", "活动偏好，如户外、室内、水上、文化体验", false)
                .string("participant_profile", "年龄、体力和人员构成", false)
                .string("transport_preference", "交通偏好", false)
                .string("accommodation_preference", "住宿要求", false)
                .string("meal_preferences", "餐饮、忌口或过敏", false)
                .string("special_requirements", "安全、无障碍、会议室、发票等要求", false)
                .arrayOfScalar("priorities", "用户明确提出的优先因素，按重要程度排列；未提出时不要填写", "string", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            ObjectNode args = (ObjectNode) objectMapper.readTree(argumentsJson);
            return objectMapper.writeValueAsString(planService.handle(args));
        } catch (Exception e) {
            log.error("travel_collect 执行失败 | error={}", e.getMessage());
            return error("旅游需求收集失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message);
        return node.toString();
    }
}
