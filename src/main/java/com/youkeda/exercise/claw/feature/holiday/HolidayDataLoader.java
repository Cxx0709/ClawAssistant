package com.youkeda.exercise.claw.feature.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中国法定节假日数据加载器
 *
 * <p>从 {@code classpath:holidays/holidays-{year}.json} 加载节假日数据。
 * 每年一个 JSON 文件，国务院发布后更新（约每年 11 月加下一年文件）。
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 缓存按年加载的数据。
 * 启动时自动扫描并加载所有已有年份的数据文件。
 */
@Component
public class HolidayDataLoader {

    private static final Logger log = LoggerFactory.getLogger(HolidayDataLoader.class);

    /** classpath 扫描路径 */
    private static final String SCAN_PATH = "classpath:holidays/holidays-*.json";

    private final ObjectMapper objectMapper;
    private final Map<Integer, YearData> cache = new ConcurrentHashMap<>();

    /**
     * 单年的节假日数据（包级可见，供 HolidayCheckFunction 使用）
     */
    public record YearData(
            int year,
            List<HolidayRange> holidays,
            Set<LocalDate> swapWorkdays
    ) {}

    /**
     * 节假日区间
     */
    public record HolidayRange(LocalDate start, LocalDate end, String name) {
        /**
         * 判断日期是否在此区间内（含首尾）
         */
        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    public HolidayDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadAll() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(SCAN_PATH);
            log.info("扫描到 {} 个节假日数据文件", resources.length);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    int year = root.get("year").asInt();

                    // 解析节假日区间
                    List<HolidayRange> holidays = parseHolidays(root, year);

                    // 解析调休工作日
                    List<LocalDate> swapList = parseSwapWorkdays(root);
                    Set<LocalDate> swapWorkdays = new HashSet<>(swapList);

                    cache.put(year, new YearData(year, holidays, swapWorkdays));
                    log.info("已加载 {} 年节假日数据：{} 个节假日区间，{} 个调休工作日",
                            year, holidays.size(), swapWorkdays.size());

                } catch (Exception e) {
                    log.warn("加载节假日数据文件失败: {} | {}", resource.getFilename(), e.getMessage());
                }
            }

            if (cache.isEmpty()) {
                log.warn("未加载到任何节假日数据！请检查 classpath:holidays/holidays-*.json 是否存在");
            } else {
                log.info("节假日数据加载完成，共 {} 年", cache.size());
            }

        } catch (Exception e) {
            log.error("扫描节假日数据文件失败", e);
        }
    }

    /**
     * 获取某年的节假日数据，不存在返回 null
     */
    public YearData getYear(Year year) {
        return cache.get(year.getValue());
    }

    /**
     * 判断指定日期是否为法定节假日
     */
    public boolean isHoliday(LocalDate date) {
        YearData data = cache.get(date.getYear());
        if (data == null) return false;
        return data.holidays().stream().anyMatch(h -> h.contains(date));
    }

    /**
     * 判断指定日期是否为调休工作日
     */
    public boolean isSwapWorkday(LocalDate date) {
        YearData data = cache.get(date.getYear());
        if (data == null) return false;
        return data.swapWorkdays().contains(date);
    }

    /**
     * 获取日期所属的节假日名称（仅当在节假日区间内时返回）
     */
    public String getHolidayName(LocalDate date) {
        YearData data = cache.get(date.getYear());
        if (data == null) return null;
        return data.holidays().stream()
                .filter(h -> h.contains(date))
                .map(HolidayRange::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取某年所有已加载的年份
     */
    Set<Integer> getLoadedYears() {
        return Collections.unmodifiableSet(cache.keySet());
    }

    /**
     * 判断某年是否有数据
     */
    boolean hasYear(Year year) {
        return cache.containsKey(year.getValue());
    }

    // ========== JSON 解析 ==========

    private List<HolidayRange> parseHolidays(JsonNode root, int year) {
        JsonNode holidaysNode = root.get("holidays");
        if (holidaysNode == null || !holidaysNode.isArray()) {
            log.warn("{} 年数据缺少 holidays 或格式错误", year);
            return List.of();
        }

        List<HolidayRange> result = new ArrayList<>();
        for (JsonNode node : holidaysNode) {
            LocalDate start = LocalDate.parse(node.get("start").asText());
            LocalDate end = LocalDate.parse(node.get("end").asText());
            String name = node.get("name").asText();
            result.add(new HolidayRange(start, end, name));
        }
        return Collections.unmodifiableList(result);
    }

    private List<LocalDate> parseSwapWorkdays(JsonNode root) {
        JsonNode swapNode = root.get("swapWorkdays");
        if (swapNode == null || !swapNode.isArray()) {
            return List.of();
        }

        List<LocalDate> result = new ArrayList<>();
        for (JsonNode node : swapNode) {
            result.add(LocalDate.parse(node.asText()));
        }
        return result;
    }
}
