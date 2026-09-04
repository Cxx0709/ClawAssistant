package com.youkeda.exercise.claw.tool.travel;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.artifact.ArtifactCollector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.feature.travel.TravelPlanDraft;
import com.youkeda.exercise.claw.feature.travel.TravelPlanOption;
import com.youkeda.exercise.claw.feature.travel.TravelPlanService;
import com.youkeda.exercise.claw.feature.travel.TravelPlanStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 选择旅游方案并处理预算决策工具。
 *
 * <p>当用户从多个候选方案中明确选择一个时调用。
 * 如果用户选择的方案超预算，本工具同时支持记录用户的超预算决定
 * （接受超支、修改到预算内、更新预算上限、查看调整选项）。
 */
@Component
public class TravelSelectOptionTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TravelSelectOptionTool.class);

    private final TravelPlanService planService;
    private final TravelPlanStateStore stateStore;

    public TravelSelectOptionTool(TravelPlanService planService,
                                        TravelPlanStateStore stateStore,
                                        ObjectMapper objectMapper,
                                        ToolRegistry registry) {
        super(registry, objectMapper);
        this.planService = planService;
        this.stateStore = stateStore;
    }

    @Override
    public String getName() {
        return "travel_select_option";
    }

    @Override
    public String getDescription() {
        return "记录用户选择的旅游方案和处理超预算决定。"
                + "当用户从候选方案中明确选择一个时调用（如选第一个、方案B等）。"
                + "如果用户选择的方案超出预算，后续可再次调用本工具来记录用户的超预算决定："
                + "ACCEPT_OVERRUN（接受超支）、REVISE_TO_BUDGET（调整到预算内）、"
                + "UPDATE_BUDGET_LIMIT（更新预算上限）、SHOW_ADJUSTMENT_OPTIONS（查看调整选项）。"
                + "用户未选择方案时不要调用。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode budgetDecision = objectMapper.createObjectNode();
        budgetDecision.put("type", "string");
        budgetDecision.put("description", "可选：用户对超预算的决定。"
                + "ACCEPT_OVERRUN=用户接受超出金额，REVISE_TO_BUDGET=用户要求调整到预算内，"
                + "UPDATE_BUDGET_LIMIT=用户更新了预算上限，SHOW_ADJUSTMENT_OPTIONS=用户要求查看可调整项目");
        budgetDecision.putArray("enum")
                .add("ACCEPT_OVERRUN").add("REVISE_TO_BUDGET")
                .add("UPDATE_BUDGET_LIMIT").add("SHOW_ADJUSTMENT_OPTIONS");

        return schema()
                .string("selected_option_id", "用户选择的候选方案标识，如 plan_a", false)
                .raw("budget_decision", budgetDecision, false)
                .number("new_budget_total", "用户更新后的总预算上限，单位元；与人均预算至少提供一个", false)
                .number("new_budget_per_person", "用户更新后的人均预算上限，单位元；与总预算至少提供一个", false)
                .string("adjustment_preferences", "用户希望保留或优先调整的内容", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            ObjectNode args = (ObjectNode) objectMapper.readTree(argumentsJson);

            // budget_decision 存在时路由到 budget_decision action
            if (args.has("budget_decision") && !args.get("budget_decision").asText().isBlank()) {
                args.put("action", "budget_decision");
            } else {
                args.put("action", "select_option");
            }
            ObjectNode result = planService.handle(args);

            // 选择方案后，如果方案有itineraryItems，生成BOARD artifact
            String selectedId = args.has("selected_option_id") ? args.get("selected_option_id").asText() : null;
            if (selectedId != null && !selectedId.isBlank()) {
                try {
                    String userId = context.userId();
                    TravelPlanDraft draft = stateStore.get(userId);
                    if (draft != null) {
                        TravelPlanOption selected = draft.getOptions().stream()
                                .filter(o -> selectedId.equals(o.getOptionId()))
                                .findFirst()
                                .orElse(null);
                        if (selected != null && selected.getItineraryItems() != null
                                && !selected.getItineraryItems().isEmpty()) {
                            // 生成BOARD artifact
                            JsonNode boardData = buildBoardData(selected);
                            ArtifactCollector artifacts = context.artifacts();
                            if (artifacts != null) {
                                artifacts.emitBoard(
                                        selected.getDisplayName(),
                                        selected.getItinerarySummary(),
                                        boardData
                                );
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("生成BOARD artifact失败", e);
                }
            }

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("travel_select_option 执行失败 | error={}", e.getMessage());
            return error("选择方案失败: " + e.getMessage());
        }
    }

    private JsonNode buildBoardData(TravelPlanOption option) {
        ObjectNode board = objectMapper.createObjectNode();
        board.put("title", option.getDisplayName());
        board.put("stats", option.getItinerarySummary());

        ArrayNode days = board.putArray("days");
        String currentDay = "";
        ObjectNode currentDayNode = null;
        ArrayNode currentItems = null;

        for (TravelPlanOption.ItineraryItem item : option.getItineraryItems()) {
            if (!item.getDay().equals(currentDay)) {
                if (currentDayNode != null) {
                    currentDayNode.set("items", currentItems);
                    days.add(currentDayNode);
                }
                currentDay = item.getDay();
                currentDayNode = objectMapper.createObjectNode();
                currentDayNode.put("label", currentDay);
                currentItems = currentDayNode.putArray("items");
            }
            ObjectNode itemNode = objectMapper.createObjectNode();
            itemNode.put("title", item.getTitle());
            itemNode.put("time", item.getTime());
            itemNode.put("status", item.getStatus());
            if (item.getNote() != null) itemNode.put("note", item.getNote());
            currentItems.add(itemNode);
        }
        if (currentDayNode != null) {
            currentDayNode.set("items", currentItems);
            days.add(currentDayNode);
        }

        return board;
    }

    private String error(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("error", message);
        return node.toString();
    }
}
