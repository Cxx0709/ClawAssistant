package com.youkeda.exercise.claw.tool.time;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * 时间查询函数（LLM Function Calling）
 *
 * <p>为 LLM 提供时间感知能力：获取当前时间、推算日期、计算日期差。
 * 纯 {@code java.time} 实现，无外部依赖。
 * 默认时区为 Asia/Shanghai（东八区）。
 *
 * <p>演示场景：团建计划设计 — LLM 通过此函数理解"这周末"、"下周五"等相对时间表达。
 */
@Component
public class TimeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(TimeTool.class);

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] WEEKDAY_CHINESE = {
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    };

    public TimeTool(ObjectMapper objectMapper, ToolRegistry functionRegistry) {
        super(functionRegistry, objectMapper);
    }

    @Override
    public String getName() {
        return "time_query";
    }

    @Override
    public String getDescription() {
        return "时间查询与日期计算工具。支持三种操作：\n" +
                "1. get_current_time — 获取当前日期时间、星期几、是否周末\n" +
                "2. date_calculate — 日期推算：支持偏移天数(delta_days)或按星期查找(target_weekday+week_context)\n" +
                "3. date_diff — 计算两个日期之间的间隔天数\n" +
                "默认时区 Asia/Shanghai，可通过 timezone 参数指定其他时区（如 America/New_York）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：get_current_time=获取当前时间, date_calculate=日期推算, date_diff=两日期差值");
        action.putArray("enum").add("get_current_time").add("date_calculate").add("date_diff");

        ObjectNode targetWeekday = objectMapper.createObjectNode();
        targetWeekday.put("type", "string");
        targetWeekday.put("description", "目标星期几：monday/tuesday/wednesday/thursday/friday/saturday/sunday。用于 date_calculate 按星期查找模式");
        targetWeekday.putArray("enum")
                .add("monday").add("tuesday").add("wednesday")
                .add("thursday").add("friday").add("saturday").add("sunday");

        ObjectNode weekContext = objectMapper.createObjectNode();
        weekContext.put("type", "string");
        weekContext.put("description", "配合 target_weekday 使用：this=本周，next=下周，last=上周。用于 date_calculate 按星期查找模式");
        weekContext.putArray("enum").add("this").add("next").add("last");

        return schema()
                .raw("action", action, true)
                .string("timezone", "时区 ID，如 Asia/Shanghai、America/New_York、Europe/London。默认 Asia/Shanghai")
                .string("base_date", "基准日期，ISO 格式 yyyy-MM-dd。默认今天。用于 date_calculate 和 date_diff")
                .string("target_date", "目标日期，ISO 格式 yyyy-MM-dd。用于 date_diff（与 base_date 计算差值）")
                .integer("delta_days", "偏移天数，正数=未来日期，负数=过去日期。用于 date_calculate 的日期偏移模式")
                .raw("target_weekday", targetWeekday, false)
                .raw("week_context", weekContext, false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode actionNode = args.get("action");
            if (actionNode == null || actionNode.asText().isEmpty()) {
                return "{\"error\": \"缺少必填参数: action\"}";
            }

            String action = actionNode.asText();
            log.info("TimeTool 执行 | action={} | args={}", action, argumentsJson);

            return switch (action) {
                case "get_current_time" -> handleGetCurrentTime(args);
                case "date_calculate" -> handleDateCalculate(args);
                case "date_diff" -> handleDateDiff(args);
                default -> "{\"error\": \"不支持的 action: " + action + "\"}";
            };

        } catch (Exception e) {
            log.error("TimeTool 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ========== Action 处理逻辑 ==========

    /**
     * 获取当前时间
     */
    private String handleGetCurrentTime(JsonNode args) {
        String timezoneId = args.has("timezone") ? args.get("timezone").asText() : DEFAULT_TIMEZONE;
        ZoneId zone = ZoneId.of(timezoneId);
        ZonedDateTime now = ZonedDateTime.now(zone);

        LocalDate today = now.toLocalDate();
        int weekdayValue = today.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "get_current_time");
        result.put("datetime", now.format(DATETIME_FORMATTER));
        result.put("date", today.format(DATE_FORMATTER));
        result.put("weekday", weekdayValue);
        result.put("weekday_name", WEEKDAY_CHINESE[weekdayValue - 1]);
        result.put("is_weekend", weekdayValue >= 6);
        result.put("timezone", timezoneId);

        return result.toString();
    }

    /**
     * 日期推算：偏移天数模式 或 按星期查找模式
     */
    private String handleDateCalculate(JsonNode args) {
        String timezoneId = args.has("timezone") ? args.get("timezone").asText() : DEFAULT_TIMEZONE;
        ZoneId zone = ZoneId.of(timezoneId);
        LocalDate baseDate = parseBaseDate(args, zone);
        String baseWeekday = WEEKDAY_CHINESE[baseDate.getDayOfWeek().getValue() - 1];

        // 模式A：delta_days 偏移
        if (args.has("delta_days")) {
            int delta = args.get("delta_days").asInt();
            LocalDate result = baseDate.plusDays(delta);
            return buildCalculateResult(baseDate, baseWeekday, result);
        }

        // 模式B：target_weekday + week_context
        if (args.has("target_weekday")) {
            DayOfWeek targetDow = parseWeekday(args.get("target_weekday").asText());
            if (targetDow == null) {
                return "{\"error\": \"无效的 target_weekday，支持: monday~sunday\"}";
            }

            String context = args.has("week_context") ? args.get("week_context").asText() : "this";
            LocalDate result = findWeekdayInContext(baseDate, targetDow, context);
            return buildCalculateResult(baseDate, baseWeekday, result);
        }

        return "{\"error\": \"date_calculate 缺少参数，请提供 delta_days 或 target_weekday[+week_context]\"}";
    }

    /**
     * 计算两个日期的间隔
     */
    private String handleDateDiff(JsonNode args) {
        String timezoneId = args.has("timezone") ? args.get("timezone").asText() : DEFAULT_TIMEZONE;
        ZoneId zone = ZoneId.of(timezoneId);
        LocalDate fromDate = parseBaseDate(args, zone);

        if (!args.has("target_date")) {
            return "{\"error\": \"date_diff 缺少必填参数: target_date\"}";
        }
        LocalDate toDate = LocalDate.parse(args.get("target_date").asText(), DATE_FORMATTER);

        long diffDays = ChronoUnit.DAYS.between(fromDate, toDate);
        long diffWeeks = diffDays / 7;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("action", "date_diff");
        result.put("from_date", fromDate.format(DATE_FORMATTER));
        result.put("to_date", toDate.format(DATE_FORMATTER));
        result.put("diff_days", Math.abs(diffDays));
        result.put("diff_weeks", Math.abs(diffWeeks));
        if (diffDays < 0) {
            result.put("direction", "past");
        } else if (diffDays > 0) {
            result.put("direction", "future");
        } else {
            result.put("direction", "same");
        }

        return result.toString();
    }

    // ========== 工具方法 ==========

    private LocalDate parseBaseDate(JsonNode args, ZoneId zone) {
        if (args.has("base_date")) {
            try {
                return LocalDate.parse(args.get("base_date").asText(), DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                log.warn("base_date 解析失败 | value={}", args.get("base_date").asText());
                // fallback 到当前日期
            }
        }
        return ZonedDateTime.now(zone).toLocalDate();
    }

    /**
     * 解析星期几字符串为 DayOfWeek
     */
    private DayOfWeek parseWeekday(String weekday) {
        return switch (weekday.toLowerCase()) {
            case "monday" -> DayOfWeek.MONDAY;
            case "tuesday" -> DayOfWeek.TUESDAY;
            case "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thursday" -> DayOfWeek.THURSDAY;
            case "friday" -> DayOfWeek.FRIDAY;
            case "saturday" -> DayOfWeek.SATURDAY;
            case "sunday" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /**
     * 在给定上下文周中查找目标星期几
     */
    private LocalDate findWeekdayInContext(LocalDate baseDate, DayOfWeek targetDow, String context) {
        // 找到 baseDate 所在周的周一
        LocalDate mondayOfWeek = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 按上下文偏移周
        LocalDate contextStart = switch (context) {
            case "next" -> mondayOfWeek.plusWeeks(1);
            case "last" -> mondayOfWeek.minusWeeks(1);
            default -> mondayOfWeek; // "this"
        };

        // 从 contextStart（周一）开始，找到目标星期几
        return contextStart.with(TemporalAdjusters.nextOrSame(targetDow));
    }

    /**
     * 构建 date_calculate 的返回 JSON
     */
    private String buildCalculateResult(LocalDate baseDate, String baseWeekday, LocalDate result) {
        DayOfWeek resultDow = result.getDayOfWeek();
        String resultWeekdayName = WEEKDAY_CHINESE[resultDow.getValue() - 1];
        long diff = ChronoUnit.DAYS.between(baseDate, result);

        String description;
        if (diff == 0) {
            description = "就是今天(" + result.format(DATE_FORMATTER) + " " + resultWeekdayName + ")";
        } else if (diff > 0) {
            description = "从" + baseDate.format(DATE_FORMATTER) + "(" + baseWeekday + ") 往后 " + diff + " 天";
        } else {
            description = "从" + baseDate.format(DATE_FORMATTER) + "(" + baseWeekday + ") 往前 " + Math.abs(diff) + " 天";
        }

        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("action", "date_calculate");
        resultJson.put("from_date", baseDate.format(DATE_FORMATTER));
        resultJson.put("from_weekday", baseWeekday);
        resultJson.put("result_date", result.format(DATE_FORMATTER));
        resultJson.put("result_weekday", resultWeekdayName);
        resultJson.put("diff_days", diff);
        resultJson.put("description", description);

        return resultJson.toString();
    }
}
