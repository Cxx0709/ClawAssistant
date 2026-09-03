package com.youkeda.exercise.claw.tool.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.scout.feedback.ScoutFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 推荐反馈工具：用户对信息猎手推荐结果给出"有用/没用"反馈。
 * 反馈会持久化，后续推荐时用于调整匹配权重。
 */
@Component
public class ScoutFeedbackTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ScoutFeedbackTool.class);

    private final ScoutFeedbackRepository feedbackRepository;

    public ScoutFeedbackTool(ObjectMapper objectMapper,
                              ToolRegistry registry,
                              ScoutFeedbackRepository feedbackRepository) {
        super(registry, objectMapper);
        this.feedbackRepository = feedbackRepository;
    }

    @Override
    public String getName() {
        return "scout_feedback";
    }

    @Override
    public String getDescription() {
        return "记录用户对信息猎手推荐结果的反馈。当用户说「这条有用」「这个不需要」「以后别推这种」时调用。\n"
                + "rating: USEFUL=有用, NOT_USEFUL=没用\n"
                + "topic: 用户反馈涉及的主题/关键词（如「AI安全」「前端教程」），用于后续调整推荐。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode rating = objectMapper.createObjectNode();
        rating.put("type", "string");
        rating.put("description", "反馈类型");
        rating.putArray("enum").add("USEFUL").add("NOT_USEFUL");

        return schema()
                .string("recommendation_id", "推荐结果ID（可选）", false)
                .string("title", "推荐标题（可选）", false)
                .raw("rating", rating, true)
                .string("topic", "反馈涉及的主题关键词", false)
                .string("reason", "反馈原因（可选）", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String rating = args.path("rating").asText("").toUpperCase();
            if (!"USEFUL".equals(rating) && !"NOT_USEFUL".equals(rating)) {
                return error("rating 必须是 USEFUL 或 NOT_USEFUL");
            }

            feedbackRepository.save(
                    args.path("recommendation_id").asText(""),
                    args.path("title").asText(""),
                    args.path("topic").asText(""),
                    rating,
                    args.path("reason").asText(""));

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "SUCCESS");
            result.put("rating", rating);
            result.put("message", "USEFUL".equals(rating)
                    ? "已记录正面反馈，后续会增加类似推荐"
                    : "已记录负面反馈，后续会减少类似推荐");
            return result.toString();
        } catch (Exception e) {
            log.error("推荐反馈工具执行失败 | args={}", argumentsJson, e);
            return error("反馈记录失败");
        }
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message);
        return node.toString();
    }
}
