package com.youkeda.exercise.claw.tool.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.holiday.HolidayDataLoader;
import com.youkeda.exercise.claw.domain.holiday.DayType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 中国法定节假日/调休查询函数（LLM Function Calling）
 *
 * <p>为 LLM 提供中国法定节假日查询能力。LLM 不知道中国的放假调休安排，
 * 编出来的日期大概率是错的。此函数通过内置的国务院数据（硬编码 JSON），
 * 返回准确的日期类型判断和团建适宜度评分。
 *
 * <p>与 {@link HolidayDataLoader} 配合使用，数据文件位于
 * {@code classpath:holidays/holidays-{year}.json}，每年更新一次。
 *
 * <p>演示场景：团建方案规划 — LLM 查询"这周五是工作日还是调休上班"、
 * "五一期间哪天适合团建"等。
 *
 * <p>注册方式：{@link ToolRegistry}（与 TimeFunction 相同模式）
 */
@Component
public class HolidayCheckTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(HolidayCheckTool.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] WEEKDAY_CN = {
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    };

    private final HolidayDataLoader dataLoader;

    public HolidayCheckTool(ObjectMapper objectMapper,
                                ToolRegistry functionRegistry,
                                HolidayDataLoader dataLoader) {
        super(functionRegistry, objectMapper);
        this.dataLoader = dataLoader;
    }

    @Override
    public String getName() {
        return "holiday_check";
    }

    @Override
    public String getDescription() {
        return "中国法定节假日/调休查询。查询指定日期或日期范围在中国法定节假日体系中的类型：\n" +
                "holiday（法定假日如国庆节）、swap_workday（调休工作日/周末上班）、\n" +
                "weekend（普通周末）、weekday（普通工作日）。\n" +
                "每个日期附带团队建设（团建）适宜度评分（0-10）和中文字面建议。\n" +
                "支持查询邻近节假日影响（check_adjacent=true 时）。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("date", "查询的目标日期，ISO 格式 yyyy-MM-dd。与 date_range 二选一")
                .object("date_range", "查询的日期范围（含首尾）。与 date 二选一", false)
                    .string("start", "起始日期 yyyy-MM-dd", true)
                    .string("end", "结束日期 yyyy-MM-dd", true)
                    .end()
                .bool("check_adjacent", "是否同时检查日期前后各 3 天的状态，评估邻近节假日影响。默认 false。仅单日期查询时有效")
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            log.info("HolidayCheckTool 执行 | args={}", argumentsJson);

            // 判断是单日期查询还是范围查询
            if (args.has("date_range")) {
                return handleDateRange(args);
            } else if (args.has("date")) {
                return handleSingleDate(args);
            } else {
                return "{\"error\": \"缺少查询参数，请提供 date 或 date_range\"}";
            }

        } catch (Exception e) {
            log.error("HolidayCheckTool 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ========== 单日期查询 ==========

    private String handleSingleDate(JsonNode args) {
        LocalDate date = parseDate(args.get("date").asText());
        if (date == null) {
            return "{\"error\": \"无效的日期格式，请使用 yyyy-MM-dd\"}";
        }

        boolean checkAdjacent = args.has("check_adjacent") && args.get("check_adjacent").asBoolean();

        DayInfo info = classifyDate(date);
        ObjectNode result = buildDateResult(date, info);

        if (checkAdjacent) {
            result.set("adjacent_impact", buildAdjacentImpact(date));
        }

        return result.toString();
    }

    // ========== 日期范围查询 ==========

    private String handleDateRange(JsonNode args) {
        JsonNode range = args.get("date_range");
        LocalDate start = parseDate(range.get("start").asText());
        LocalDate end = parseDate(range.get("end").asText());

        if (start == null || end == null) {
            return "{\"error\": \"无效的日期范围，请使用 yyyy-MM-dd 格式\"}";
        }
        if (end.isBefore(start)) {
            return "{\"error\": \"结束日期不能早于开始日期\"}";
        }

        List<DayInfo> dayInfos = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            dayInfos.add(classifyDate(d));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("start", start.format(DATE_FMT));
        result.put("end", end.format(DATE_FMT));

        ArrayNode daysArray = result.putArray("days");
        for (DayInfo info : dayInfos) {
            daysArray.add(buildDateResult(info.date(), info));
        }

        result.set("summary", buildRangeSummary(start, end, dayInfos));

        return result.toString();
    }

    // ========== 日期分类逻辑 ==========

    /**
     * 对单日进行分类，返回完整信息
     */
    private DayInfo classifyDate(LocalDate date) {
        DayType dayType;
        String holidayName = null;

        if (dataLoader.isHoliday(date)) {
            dayType = DayType.HOLIDAY;
            holidayName = dataLoader.getHolidayName(date);
        } else if (dataLoader.isSwapWorkday(date)) {
            dayType = DayType.SWAP_WORKDAY;
        } else if (isWeekend(date)) {
            dayType = DayType.WEEKEND;
        } else {
            dayType = DayType.WEEKDAY;
        }

        int score = calcTeamBuildingScore(dayType);
        String advice = getTeamBuildingAdvice(dayType, date, holidayName);

        // 节假日上下文：调休日说明
        String holidayContext = buildHolidayContext(date, dayType);

        return new DayInfo(date, dayType, holidayName, holidayContext, score, advice);
    }

    /**
     * 判断是否为周末
     */
    private boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * 计算团建适宜度评分（0-10）
     */
    private int calcTeamBuildingScore(DayType dayType) {
        return switch (dayType) {
            case WEEKEND -> 8;            // 最佳选择
            case HOLIDAY -> 4;            // 假日人多，但时间充裕
            case SWAP_WORKDAY -> 2;       // 人不齐
            case WEEKDAY -> 4;            // 需请假
        };
    }

    /**
     * 获取团建文字建议
     */
    private String getTeamBuildingAdvice(DayType dayType, LocalDate date, String holidayName) {
        return switch (dayType) {
            case WEEKEND -> "普通周末，适合团建。建议提前预订场地";
            case HOLIDAY -> {
                if (holidayName != null && isFirstDayOfHoliday(date)) {
                    yield holidayName + "第1天，人潮高峰，场地涨价明显。建议选择假期中后段或避开";
                } else if (holidayName != null) {
                    yield holidayName + "期间，人流量较大，场地可能涨价。建议选择人少的小众场地";
                } else {
                    yield "法定假日期间，注意场地需提前预约";
                }
            }
            case SWAP_WORKDAY -> "调休工作日，大部分团队成员需上班，不建议安排团建";
            case WEEKDAY -> "普通工作日，需全员请假。建议提前协调团队时间";
        };
    }

    /**
     * 构建节假日上下文说明（调休关联信息）
     */
    private String buildHolidayContext(LocalDate date, DayType dayType) {
        if (dayType == DayType.SWAP_WORKDAY) {
            // 找到最近的下一个或上一个假日
            LocalDate nearest = findNearestHoliday(date);
            if (nearest != null) {
                String name = dataLoader.getHolidayName(nearest);
                return "为" + (name != null ? name : "附近节假日") + "调休上班日";
            }
            return "为节假日调休上班日";
        }
        return null;
    }

    /**
     * 判断是否是节假日第一天（连休第一天）
     */
    private boolean isFirstDayOfHoliday(LocalDate date) {
        // 查询该日期是否在某个假日区间且是起始日
        HolidayDataLoader.YearData yearData = dataLoader.getYear(java.time.Year.of(date.getYear()));
        if (yearData == null) return false;
        return yearData.holidays().stream()
                .anyMatch(h -> h.contains(date) && h.start().equals(date));
    }

    /**
     * 查找最近的已加载节假日
     */
    private LocalDate findNearestHoliday(LocalDate date) {
        HolidayDataLoader.YearData data = dataLoader.getYear(java.time.Year.of(date.getYear()));
        if (data == null) return null;

        // 先检查当年
        LocalDate nearest = null;
        long minDiff = Long.MAX_VALUE;

        for (HolidayDataLoader.HolidayRange range : data.holidays()) {
            for (LocalDate d = range.start(); !d.isAfter(range.end()); d = d.plusDays(1)) {
                long diff = Math.abs(d.toEpochDay() - date.toEpochDay());
                if (diff < minDiff) {
                    minDiff = diff;
                    nearest = d;
                }
            }
        }

        return nearest;
    }

    // ========== 邻近节假日影响分析 ==========

    /**
     * 构建邻近节假日影响分析
     */
    private ObjectNode buildAdjacentImpact(LocalDate date) {
        ObjectNode adj = objectMapper.createObjectNode();

        // 查前后 3 天
        List<DayInfo> before = new ArrayList<>();
        List<DayInfo> after = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            before.add(classifyDate(date.minusDays(i)));
            after.add(classifyDate(date.plusDays(i)));
        }

        // 检查前后 3 天内是否有节假日
        boolean hasHolidayBefore = before.stream().anyMatch(d -> d.dayType() == DayType.HOLIDAY);
        boolean hasHolidayAfter = after.stream().anyMatch(d -> d.dayType() == DayType.HOLIDAY);
        boolean hasSwapBefore = before.stream().anyMatch(d -> d.dayType() == DayType.SWAP_WORKDAY);
        boolean hasSwapAfter = after.stream().anyMatch(d -> d.dayType() == DayType.SWAP_WORKDAY);

        adj.put("has_holiday_within_3days_before", hasHolidayBefore);
        adj.put("has_holiday_within_3days_after", hasHolidayAfter);
        adj.put("has_swap_workday_within_3days_before", hasSwapBefore);
        adj.put("has_swap_workday_within_3days_after", hasSwapAfter);

        // 查找最近节假日
        LocalDate nearestHoliday = findNearestHoliday(date);
        if (nearestHoliday != null) {
            String nearestName = dataLoader.getHolidayName(nearestHoliday);
            adj.put("nearest_holiday", nearestHoliday.format(DATE_FMT));
            if (nearestName != null) {
                adj.put("nearest_holiday_name", nearestName);
            }

            long daysDiff = Math.abs(nearestHoliday.toEpochDay() - date.toEpochDay());
            adj.put("days_to_nearest_holiday", daysDiff);
        }

        // 影响说明
        StringBuilder impactNote = new StringBuilder();
        if (hasHolidayBefore || hasSwapBefore) {
            impactNote.append("日期前有节假日/调休，团队状态可能受假期节奏影响");
        } else if (hasHolidayAfter || hasSwapAfter) {
            impactNote.append("日期后有节假日/调休，团队可能提前进入休假状态");
        } else {
            impactNote.append("附近无节假日影响，日期状态独立");
        }
        adj.put("impact_description", impactNote.toString());

        return adj;
    }

    // ========== 范围查询汇总 ==========

    private ObjectNode buildRangeSummary(LocalDate start, LocalDate end, List<DayInfo> infos) {
        ObjectNode summary = objectMapper.createObjectNode();

        long totalDays = infos.size();
        long holidays = infos.stream().filter(d -> d.dayType() == DayType.HOLIDAY).count();
        long swapWorkdays = infos.stream().filter(d -> d.dayType() == DayType.SWAP_WORKDAY).count();
        long weekends = infos.stream().filter(d -> d.dayType() == DayType.WEEKEND).count();
        long weekdays = infos.stream().filter(d -> d.dayType() == DayType.WEEKDAY).count();

        summary.put("total_days", totalDays);
        summary.put("holidays", holidays);
        summary.put("swap_workdays", swapWorkdays);
        summary.put("weekends", weekends);
        summary.put("weekdays", weekdays);

        // 推荐日期（评分 >= 6 的）
        ArrayNode recommended = summary.putArray("recommended_dates");
        infos.stream()
                .filter(d -> d.teamBuildingScore() >= 6)
                .forEach(d -> {
                    ObjectNode r = recommended.addObject();
                    r.put("date", d.date().format(DATE_FMT));
                    r.put("reason", d.teamBuildingAdvice());
                });

        // 最差日期（评分 <= 3 的）
        ArrayNode worst = summary.putArray("worst_dates");
        infos.stream()
                .filter(d -> d.teamBuildingScore() <= 3)
                .forEach(d -> {
                    ObjectNode w = worst.addObject();
                    w.put("date", d.date().format(DATE_FMT));
                    w.put("reason", d.teamBuildingAdvice());
                });

        return summary;
    }

    // ========== 构建单日 JSON 结果 ==========

    private ObjectNode buildDateResult(LocalDate date, DayInfo info) {
        DayOfWeek dow = date.getDayOfWeek();
        ObjectNode node = objectMapper.createObjectNode();

        node.put("date", date.format(DATE_FMT));
        node.put("weekday", dow.getValue());
        node.put("weekday_name", WEEKDAY_CN[dow.getValue() - 1]);
        node.put("day_type", info.dayType().name().toLowerCase());
        node.put("is_weekend", dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);

        if (info.holidayName() != null) {
            node.put("holiday_name", info.holidayName());
        }
        if (info.holidayContext() != null) {
            node.put("holiday_context", info.holidayContext());
        }

        node.put("team_building_score", info.teamBuildingScore());
        node.put("team_building_advice", info.teamBuildingAdvice());

        return node;
    }

    // ========== 记录 ==========

    /**
     * 单日分类结果内部记录
     */
    record DayInfo(
            LocalDate date,
            DayType dayType,
            String holidayName,
            String holidayContext,
            int teamBuildingScore,
            String teamBuildingAdvice
    ) {}

    // ========== 工具方法 ==========

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            log.warn("日期解析失败: {}", dateStr);
            return null;
        }
    }
}
