package com.youkeda.exercise.claw.tool.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.feature.weather.WeatherResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 天气查询函数（LLM Function Calling 适配器）
 *
 * <p>包装 {@link WeatherTool}，使其可供 LLM 通过工具调用方式使用。
 * LLM 生成 {"city": "北京"} 参数，本函数执行后返回 JSON 格式的天气数据。
 */
@Component
public class WeatherTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final com.youkeda.exercise.claw.feature.weather.WeatherService weatherTool;

    public WeatherTool(com.youkeda.exercise.claw.feature.weather.WeatherService weatherTool,
                            ObjectMapper objectMapper,
                            com.youkeda.exercise.claw.agent.runtime.ToolRegistry registry) {
        super(registry, objectMapper);
        this.weatherTool = weatherTool;
    }

    @Override
    public String getName() {
        return "weather_query";
    }

    @Override
    public String getDescription() {
        return "查询指定城市当前或出行日期的天气。完整出游方案必须在目的地和日期确定后调用。"
                + "远期日期超出可靠预报范围时返回 UNAVAILABLE，此时可用 web_search 查询同期气候参考，"
                + "但必须提醒用户出发前3至7天复查。";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("city", "城市名称，如：北京、上海、广州、深圳", true)
                .string("date", "可选，出行日期，格式 yyyy-MM-dd；不传则查询当前天气", false)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode cityNode = args.has("city") ? args.get("city") : args.get("location");
            if (cityNode == null || cityNode.asText().isBlank()) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "ERROR");
                result.put("source", "WEATHER_API");
                result.put("error", "缺少必填参数: city");
                result.put("fallback_required", false);
                return result.toString();
            }

            String city = cityNode.asText();
            log.info("WeatherTool 执行 | city={}", city);

            String dateText = args.path("date").asText("");
            LocalDate targetDate = null;
            if (!dateText.isBlank()) {
                try {
                    targetDate = LocalDate.parse(dateText);
                } catch (DateTimeParseException e) {
                    return unavailable("日期格式必须为 yyyy-MM-dd", false);
                }
                long days = ChronoUnit.DAYS.between(LocalDate.now(), targetDate);
                if (days < 0) return unavailable("不能查询过去日期的天气预报", true);
                if (days > 14) return unavailable("日期超出14天可靠预报范围", true);
            }

            WeatherResponse response = targetDate == null
                    ? weatherTool.queryWeather(city)
                    : weatherTool.queryWeather(city, targetDate);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "SUCCESS");
            result.put("source", "WEATHER_API");
            result.set("data", objectMapper.valueToTree(response));
            result.put("fallback_required", false);
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("WeatherTool 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "ERROR");
            result.put("source", "WEATHER_API");
            result.put("error", e.getMessage());
            result.put("fallback_required", true);
            return result.toString();
        }
    }

    private String unavailable(String reason, boolean webFallback) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "UNAVAILABLE");
        result.put("source", "WEATHER_API");
        result.put("reason", reason);
        result.put("fallback_required", webFallback);
        if (webFallback) {
            result.put("instruction", "可用 web_search 查询当地同期气候作为参考，并提醒出发前3至7天复查天气。不得把同期气候写成准确预报。");
        }
        return result.toString();
    }
}
