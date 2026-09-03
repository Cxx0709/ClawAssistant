package com.youkeda.exercise.claw.feature.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.infrastructure.common.ClawException;
import com.youkeda.exercise.claw.infrastructure.common.HttpClientUtil;
import com.youkeda.exercise.claw.feature.weather.WeatherConfig;
import com.youkeda.exercise.claw.feature.weather.WeatherResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 天气查询工具
 * 调用外部天气 API 获取天气信息
 */
@Component
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherConfig config;
    private final HttpClientUtil httpClient;
    private final ObjectMapper objectMapper;

    public WeatherService(WeatherConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new HttpClientUtil();
    }

    /**
     * 查询指定城市的天气
     *
     * @param city 城市名称
     * @return 天气响应数据
     * @throws ClawException 查询失败时抛出
     */
    public WeatherResponse queryWeather(String city) throws ClawException {
        try {
            // 1. 构建请求 URL（替换占位符）
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = config.getUrl()
                    .replace("{city}", encodedCity)
                    .replace("{key}", config.getKey());

            // 2. 发送 HTTP GET 请求
            String responseBody = httpClient.doGet(url);

            // 3. 解析 JSON
            return parseResponse(responseBody, city);

        } catch (Exception e) {
            if (e instanceof ClawException ce) throw ce;
            throw new ClawException("天气服务暂时不可用", e);
        }
    }

    /** 查询指定日期的天气预报。调用方应确保日期在服务支持的预报范围内。 */
    public WeatherResponse queryWeather(String city, LocalDate date) throws ClawException {
        if (date == null || date.equals(LocalDate.now())) return queryWeather(city);
        try {
            long daysFromToday = ChronoUnit.DAYS.between(LocalDate.now(), date);
            int days = (int) Math.max(1, daysFromToday + 1);
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = config.getUrl()
                    .replace("/current.json", "/forecast.json")
                    .replace("{city}", encodedCity)
                    .replace("{key}", config.getKey());
            url += "&days=" + days;
            return parseForecastResponse(httpClient.doGet(url), city, date);
        } catch (Exception e) {
            if (e instanceof ClawException ce) throw ce;
            throw new ClawException("天气预报服务暂时不可用", e);
        }
    }

    /**
     * 解析天气 API 返回的 JSON 数据
     * 支持 WeatherAPI.com 格式
     */
    private WeatherResponse parseResponse(String json, String originalCity) throws ClawException {
        try {
            JsonNode root = objectMapper.readTree(json);

            // 检查 API 是否返回错误
            JsonNode errorNode = root.get("error");
            if (errorNode != null) {
                String message = errorNode.has("message") ? errorNode.get("message").asText() : "未知错误";
                throw new ClawException("天气 API 返回错误: " + message);
            }

            // 解析天气数据
            JsonNode location = root.get("location");
            String cityName = location != null && location.has("name")
                    ? location.get("name").asText()
                    : originalCity;

            JsonNode current = root.get("current");
            String weatherDesc = "未知";
            if (current != null && current.has("condition")) {
                JsonNode condition = current.get("condition");
                weatherDesc = condition.has("text") ? condition.get("text").asText() : "未知";
            }

            double temp = current != null && current.has("temp_c") ? current.get("temp_c").asDouble() : 0.0;
            int humidity = current != null && current.has("humidity") ? current.get("humidity").asInt() : 0;

            return new WeatherResponse(cityName, weatherDesc, temp, humidity);

        } catch (Exception e) {
            if (e instanceof ClawException ce) throw ce;
            throw new ClawException("解析天气数据失败", e);
        }
    }

    private WeatherResponse parseForecastResponse(String json, String originalCity,
                                                  LocalDate targetDate) throws ClawException {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("error")) {
                String message = root.path("error").path("message").asText("未知错误");
                throw new ClawException("天气 API 返回错误: " + message);
            }
            String cityName = root.path("location").path("name").asText(originalCity);
            JsonNode forecastDays = root.path("forecast").path("forecastday");
            if (!forecastDays.isArray()) throw new ClawException("天气 API 未返回预报数据");

            for (JsonNode forecastDay : forecastDays) {
                if (targetDate.toString().equals(forecastDay.path("date").asText())) {
                    JsonNode day = forecastDay.path("day");
                    WeatherResponse response = new WeatherResponse();
                    response.setCity(cityName);
                    response.setDate(targetDate.toString());
                    response.setForecast(true);
                    response.setWeather(day.path("condition").path("text").asText("未知"));
                    response.setTemperature(day.path("avgtemp_c").asDouble());
                    response.setMinTemperature(day.path("mintemp_c").asDouble());
                    response.setMaxTemperature(day.path("maxtemp_c").asDouble());
                    response.setHumidity(day.path("avghumidity").asInt());
                    response.setChanceOfRain(day.path("daily_chance_of_rain").asInt());
                    response.setMaxWindKph(day.path("maxwind_kph").asDouble());
                    return response;
                }
            }
            throw new ClawException("目标日期不在天气 API 返回的预报范围内");
        } catch (Exception e) {
            if (e instanceof ClawException ce) throw ce;
            throw new ClawException("解析天气预报数据失败", e);
        }
    }
}
