package com.youkeda.exercise.claw.feature.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旅游/出游方案业务服务。
 *
 * <p>纯工具：接收结构化输入，返回结构化结果。
 * 不维护编排状态（stage 管理已移除），不替 LLM 决定下一步做什么。
 */
@Service
public class TravelPlanService {

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*天");
    private static final Pattern NIGHTS_PATTERN = Pattern.compile("(\\d+)\\s*晚");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Set<String> PREFERENCE_FIELDS = Set.of(
            "activity_preferences", "team_goal", "participant_profile",
            "transport_preference", "accommodation_preference",
            "meal_preferences", "special_requirements");

    private final TravelPlanStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final UserExecutionContext userExecutionContext;

    public TravelPlanService(TravelPlanStateStore stateStore, ObjectMapper objectMapper,
                                UserExecutionContext userExecutionContext) {
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.userExecutionContext = userExecutionContext;
    }

    private String resolveDefaultUserId() {
        return userExecutionContext.requireUserId();
    }

    /**
     * 处理旅游方案工具调用。
     *
     * @return 业务数据的结果描述，不包含编排指令（stage、next_tool、instruction、output_contract 均已移除）
     */
    public ObjectNode handle(JsonNode args) {
        return handle(args, resolveDefaultUserId());
    }

    /**
     * 处理旅游方案工具调用（指定 userId）。
     */
    public ObjectNode handle(JsonNode args, String userId) {
        normalizeAliases((ObjectNode) args);
        String action = text(args, "action");
        if ("reset".equals(action)) {
            stateStore.clear(userId);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "RESET");
            result.put("message", "旧方案已清除，请重新收集需求。");
            return result;
        }

        TravelPlanDraft draft = stateStore.get(userId);
        if (draft == null) draft = new TravelPlanDraft();
        if (args.has("option_count") && args.get("option_count").canConvertToInt()) {
            int optionCount = args.get("option_count").asInt();
            if (optionCount < 1 || optionCount > 5) {
                return saveError(draft, "候选方案数量必须在1到5之间。");
            }
        }
        boolean materialChange = merge(draft, args);
        if (materialChange) {
            if (!draft.getOptions().isEmpty()) invalidateAllOptions(draft);
        }

        ObjectNode result = switch (action) {
            case "save_options" -> saveOptions(draft, args);
            case "select_option" -> selectOption(draft, text(args, "selected_option_id"));
            case "combine_options" -> combineOptions(draft, args);
            case "revise_option" -> reviseOption(draft, args);
            case "budget_decision" -> handleBudgetDecision(draft, args);
            case "revise" -> handleRevision(draft, text(args, "feedback"));
            default -> collect(draft);
        };
        stateStore.save(userId, draft);
        return result;
    }

    public TravelPlanDraft getDraft() {
        return stateStore.get(resolveDefaultUserId());
    }

    // ==================== Action Handlers ====================

    private ObjectNode collect(TravelPlanDraft draft) {
        Map<String, String> missing = findMissing(draft);
        ObjectNode result = objectMapper.createObjectNode();

        if (!missing.isEmpty()) {
            result.put("status", "NEED_MORE_INFORMATION");
            ArrayNode fields = result.putArray("missing_fields");
            ArrayNode questions = result.putArray("questions");
            missing.forEach((field, question) -> {
                fields.add(field);
                questions.add(question);
            });
        } else {
            result.put("status", "ALL_COLLECTED");
            result.put("message", "需求信息已齐全。");
        }
        refreshState(result, draft);
        return result;
    }

    private ObjectNode saveOptions(TravelPlanDraft draft, JsonNode args) {
        JsonNode optionNodes = args.get("options");
        if (optionNodes == null || !optionNodes.isArray() || optionNodes.isEmpty()) {
            return saveError(draft, "save_options 至少需要一个候选方案。");
        }
        if (optionNodes.size() > 5) {
            return saveError(draft, "候选方案最多保存5个，请保留差异最明显的方案。");
        }
        if (optionNodes.size() != draft.getOptionCount()) {
            return saveError(draft, "当前应生成" + draft.getOptionCount()
                    + "个候选方案，实际收到" + optionNodes.size() + "个。");
        }

        List<TravelPlanOption> options = new ArrayList<>();
        for (JsonNode node : optionNodes) {
            String id = text(node, "option_id");
            if (id.isBlank()) return saveError(draft, "每个候选方案都必须提供 option_id。");
            TravelPlanOption option = new TravelPlanOption();
            option.setOptionId(id);
            option.setDisplayName(defaultText(text(node, "display_name"), id));
            option.setPositioning(text(node, "positioning"));
            option.setHighlights(text(node, "highlights"));
            option.setItinerarySummary(text(node, "itinerary_summary"));
            options.add(option);
        }

        draft.setOptions(options);
        draft.setSelectedOptionId(null);
        draft.setOptionSetVersion(Math.max(1, draft.getOptionSetVersion() + 1));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "OPTIONS_SAVED");
        result.put("option_count", options.size());
        refreshState(result, draft);
        return result;
    }

    private ObjectNode selectOption(TravelPlanDraft draft, String optionId) {
        TravelPlanOption selected = findOption(draft, optionId);
        if (selected == null) return saveError(draft, "未找到候选方案：" + optionId);

        draft.setSelectedOptionId(selected.getOptionId());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "OPTION_SELECTED");
        result.put("selected_option_id", selected.getOptionId());
        result.put("option_name", selected.getDisplayName());
        if (selected.getCostResult() != null) {
            result.set("cost", selected.getCostResult());
        }
        refreshState(result, draft);
        return result;
    }

    private ObjectNode combineOptions(TravelPlanDraft draft, JsonNode args) {
        JsonNode sourceIds = args.get("source_option_ids");
        if (sourceIds == null || !sourceIds.isArray() || sourceIds.size() < 2) {
            return saveError(draft, "组合方案至少需要两个 source_option_ids。");
        }
        String id = defaultText(text(args, "option_id"), "COMBINED-" + (draft.getOptionSetVersion() + 1));
        TravelPlanOption combined = new TravelPlanOption();
        combined.setOptionId(id);
        combined.setDisplayName(defaultText(text(args, "display_name"), "组合方案"));
        combined.setPositioning(defaultText(text(args, "positioning"), "用户组合方案"));
        combined.setHighlights(text(args, "highlights"));
        combined.setItinerarySummary(text(args, "itinerary_summary"));
        combined.setPlanStatus(PlanStatus.CANDIDATE);
        draft.getOptions().add(combined);
        draft.setSelectedOptionId(id);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "OPTION_COMBINED");
        result.put("message", "已生成组合方案，请重新核算费用。");
        refreshState(result, draft);
        return result;
    }

    private ObjectNode reviseOption(TravelPlanDraft draft, JsonNode args) {
        TravelPlanOption option = findOption(draft, text(args, "option_id"));
        if (option == null) return saveError(draft, "未找到需要修改的候选方案。");

        option.setVersion(Math.max(1, option.getVersion()) + 1);
        if (hasText(args, "display_name")) option.setDisplayName(text(args, "display_name"));
        if (hasText(args, "positioning")) option.setPositioning(text(args, "positioning"));
        if (hasText(args, "highlights")) option.setHighlights(text(args, "highlights"));
        if (hasText(args, "itinerary_summary")) option.setItinerarySummary(text(args, "itinerary_summary"));
        option.setCostStatus(CostStatus.STALE);
        option.setCostResult(null);
        option.setPlanStatus(PlanStatus.NEEDS_REVIEW);
        draft.setSelectedOptionId(option.getOptionId());
        draft.setCostStatus(CostStatus.STALE);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "OPTION_REVISED");
        result.put("message", "方案已修改，请重新核算费用。");
        refreshState(result, draft);
        return result;
    }

    private ObjectNode handleBudgetDecision(TravelPlanDraft draft, JsonNode args) {
        TravelPlanOption selected = findOption(draft, draft.getSelectedOptionId());
        if (selected == null) return saveError(draft, "请先选择一个候选方案。");

        String decision = text(args, "budget_decision");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "BUDGET_DECISION_" + decision);
        result.put("message", switch (decision) {
            case "ACCEPT_OVERRUN" -> "用户已接受超预算。";
            case "REVISE_TO_BUDGET" -> "用户要求调整到预算内。";
            case "UPDATE_BUDGET_LIMIT" -> "用户更新了预算上限。";
            case "SHOW_ADJUSTMENT_OPTIONS" -> "用户要求查看可调整项目。";
            default -> "未知的预算决定。";
        });

        switch (decision) {
            case "ACCEPT_OVERRUN" -> {
                JsonNode cost = selected.getCostResult();
                draft.setAcceptedOverrunAmount(number(cost, "overrunMax"));
                draft.setAcceptedOverrunRate(number(cost, "overrunRateMax"));
            }
            case "UPDATE_BUDGET_LIMIT" -> {
                if (args.has("new_budget_total") && args.get("new_budget_total").isNumber()) {
                    draft.setBudgetTotal(args.get("new_budget_total").asDouble());
                    draft.setBudgetPerPerson(null);
                } else if (args.has("new_budget_per_person")
                        && args.get("new_budget_per_person").isNumber()) {
                    draft.setBudgetPerPerson(args.get("new_budget_per_person").asDouble());
                    draft.setBudgetTotal(null);
                } else {
                    return saveError(draft, "更新预算上限时必须提供新的总预算或人均预算。");
                }
            }
            case "REVISE_TO_BUDGET" -> {
                if (hasText(args, "adjustment_preferences")) {
                    result.put("adjustment_preferences", text(args, "adjustment_preferences"));
                }
            }
            default -> {}
        }

        refreshState(result, draft);
        return result;
    }

    private ObjectNode handleRevision(TravelPlanDraft draft, String feedback) {
        draft.setLastFeedback(feedback);
        draft.setVersion(Math.max(1, draft.getVersion()) + 1);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "REVISION_RECORDED");
        result.put("feedback", feedback);
        refreshState(result, draft);
        return result;
    }

    // ==================== Business Helpers ====================

    private Map<String, String> findMissing(TravelPlanDraft draft) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (blank(draft.getDepartureCity())) missing.put("departure_city", "从哪里出发或在哪里集合？");
        if (draft.getParticipantCount() == null || draft.getParticipantCount() <= 0)
            missing.put("participant_count", "预计多少人参加？");
        if (blank(draft.getTravelDate()))
            missing.put("travel_date", "计划什么时候出发？具体日期或大致时间都可以。");
        if (blank(draft.getDuration()) || draft.getDays() == null || draft.getDays() <= 0)
            missing.put("duration", "计划玩多久？请说明天数，例如1天或2天1晚。");
        if (!positive(draft.getBudgetTotal()) && !positive(draft.getBudgetPerPerson()))
            missing.put("budget", "可接受的预算上限是多少？请提供总预算或人均预算。");
        if (blank(draft.getDestination()) && blank(draft.getTravelScope()))
            missing.put("destination_or_scope", "有确定目的地吗？没有的话，请告诉我可接受的出行范围。");
        return missing;
    }

    private boolean merge(TravelPlanDraft draft, JsonNode args) {
        boolean changed = false;
        if (hasText(args, "departure_city")) {
            changed |= !Objects.equals(draft.getDepartureCity(), text(args, "departure_city"));
            draft.setDepartureCity(text(args, "departure_city"));
        }
        if (args.has("participant_count") && args.get("participant_count").canConvertToInt()) {
            int value = args.get("participant_count").asInt();
            changed |= !Objects.equals(draft.getParticipantCount(), value);
            draft.setParticipantCount(value);
        }
        if (hasText(args, "travel_date")) {
            String travelDate = normalizeYearlessDate(text(args, "travel_date"));
            changed |= !Objects.equals(draft.getTravelDate(), travelDate);
            draft.setTravelDate(travelDate);
        }
        if (hasText(args, "duration")) {
            String duration = text(args, "duration");
            changed |= !Objects.equals(draft.getDuration(), duration);
            draft.setDuration(duration);
            parseDuration(draft, duration);
        }
        if (args.has("days") && args.get("days").canConvertToInt()) {
            int value = args.get("days").asInt();
            changed |= !Objects.equals(draft.getDays(), value);
            draft.setDays(value);
        }
        if (args.has("nights") && args.get("nights").canConvertToInt()) {
            int value = args.get("nights").asInt();
            changed |= !Objects.equals(draft.getNights(), value);
            draft.setNights(value);
        }
        if (args.has("option_count") && args.get("option_count").canConvertToInt()) {
            draft.setOptionCount(args.get("option_count").asInt());
        }
        if (args.has("budget_total") && args.get("budget_total").isNumber()) {
            double value = args.get("budget_total").asDouble();
            changed |= !Objects.equals(draft.getBudgetTotal(), value);
            draft.setBudgetTotal(value);
        }
        if (args.has("budget_per_person") && args.get("budget_per_person").isNumber()) {
            double value = args.get("budget_per_person").asDouble();
            changed |= !Objects.equals(draft.getBudgetPerPerson(), value);
            draft.setBudgetPerPerson(value);
        }
        if (hasText(args, "budget_level")) draft.setBudgetLevel(text(args, "budget_level"));
        if (args.has("max_overrun_amount") && args.get("max_overrun_amount").isNumber())
            draft.setMaxOverrunAmount(args.get("max_overrun_amount").asDouble());
        if (args.has("max_overrun_rate") && args.get("max_overrun_rate").isNumber())
            draft.setMaxOverrunRate(args.get("max_overrun_rate").asDouble());
        if (hasText(args, "destination")) {
            changed |= !Objects.equals(draft.getDestination(), text(args, "destination"));
            draft.setDestination(text(args, "destination"));
        }
        if (hasText(args, "travel_scope")) {
            changed |= !Objects.equals(draft.getTravelScope(), text(args, "travel_scope"));
            draft.setTravelScope(text(args, "travel_scope"));
        }

        for (String field : PREFERENCE_FIELDS) {
            if (hasText(args, field)) {
                String value = text(args, field);
                changed |= !Objects.equals(draft.getPreferences().get(field), value);
                draft.getPreferences().put(field, value);
            }
        }
        JsonNode priorities = args.get("priorities");
        if (priorities != null && priorities.isArray()) {
            List<String> values = new ArrayList<>();
            priorities.forEach(node -> {
                if (!node.asText().isBlank()) values.add(node.asText());
            });
            changed |= !draft.getPriorities().equals(values);
            draft.setPriorities(values);
        }
        draft.setPlanMode(draft.getPriorities().isEmpty()
                ? PlanMode.BALANCED_DEFAULT : PlanMode.PRIORITY);
        return changed;
    }

    private void invalidateAllOptions(TravelPlanDraft draft) {
        draft.getOptions().forEach(option -> {
            option.setCostStatus(CostStatus.STALE);
            option.setCostResult(null);
            option.setPlanStatus(PlanStatus.NEEDS_REVIEW);
        });
    }

    // ==================== Formatting ====================

    private void refreshState(ObjectNode result, TravelPlanDraft draft) {
        result.put("plan_mode", draft.getPlanMode().value());
        result.put("version", draft.getVersion());
        result.set("collected_information", objectMapper.valueToTree(draft));
    }

    private ObjectNode saveError(TravelPlanDraft draft, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "INVALID_ARGUMENT");
        result.put("error", message);
        refreshState(result, draft);
        return result;
    }

    private TravelPlanOption findOption(TravelPlanDraft draft, String optionId) {
        if (blank(optionId)) return null;
        return draft.getOptions().stream()
                .filter(option -> optionId.equals(option.getOptionId()))
                .findFirst().orElse(null);
    }

    private void parseDuration(TravelPlanDraft draft, String duration) {
        Matcher days = DAYS_PATTERN.matcher(duration);
        if (days.find()) draft.setDays(Integer.parseInt(days.group(1)));
        Matcher nights = NIGHTS_PATTERN.matcher(duration);
        if (nights.find()) draft.setNights(Integer.parseInt(nights.group(1)));
        else if (draft.getDays() != null) draft.setNights(Math.max(draft.getDays() - 1, 0));
    }

    private static String normalizeYearlessDate(String value) {
        if (blank(value) || value.matches(".*\\d{4}[-年].*")) return value;
        Matcher matcher = MONTH_DAY_PATTERN.matcher(value);
        if (!matcher.find()) return value;
        int month = Integer.parseInt(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        try {
            LocalDate resolved = LocalDate.of(today.getYear(), month, day);
            if (resolved.isBefore(today)) resolved = resolved.plusYears(1);
            return resolved.toString();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== Alias Normalization ====================

    /**
     * 兼容模型偶尔生成的常见别名和驼峰字段，统一转换为工具契约中的 snake_case。
     */
    private void normalizeAliases(ObjectNode args) {
        copyAlias(args, "departure_city", "origin", "departureCity");
        copyAlias(args, "participant_count", "people", "headcount", "participantCount");
        copyAlias(args, "travel_date", "start_date", "travelDate", "startDate");
        copyAlias(args, "budget_total", "budget", "total_budget", "budgetTotal");
        copyAlias(args, "budget_per_person", "per_person_budget", "budgetPerPerson");
        copyAlias(args, "selected_option_id", "selectedOptionId");

        JsonNode options = args.get("options");
        if (options != null && options.isArray()) {
            for (JsonNode option : options) {
                if (!(option instanceof ObjectNode object)) continue;
                copyAlias(object, "option_id", "optionId", "plan_id", "planId");
                copyAlias(object, "display_name", "displayName", "plan_name", "planName");
                copyAlias(object, "itinerary_summary", "itinerarySummary");
            }
        }
    }

    private void copyAlias(ObjectNode object, String canonical, String... aliases) {
        if (object.has(canonical)) return;
        for (String alias : aliases) {
            JsonNode value = object.get(alias);
            if (value != null && !value.isNull()) {
                object.set(canonical, value);
                return;
            }
        }
    }

    private static boolean positive(Double value) {
        return value != null && value > 0;
    }

    private static boolean hasText(JsonNode node, String field) {
        return !text(node, field).isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private static String defaultText(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isNumber() ? value.asDouble() : null;
    }
}
