package com.youkeda.exercise.claw.feature.scout.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationFreshness;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import com.youkeda.exercise.claw.feature.websearch.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 搜索采集器
 *
 * 复用现有 Tavily SearchService
 */
@Component
public class WebSearchCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(WebSearchCollector.class);

    private final SearchService searchService;
    private final ScoutProperties props;
    private final ObjectMapper objectMapper;

    public WebSearchCollector(SearchService searchService,
                              ScoutProperties props,
                              ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "WEB_SEARCH";
    }

    @Override
    public List<InformationItem> collect(SearchTask task) {
        List<InformationItem> items = new ArrayList<>();
        try {
            // 信息猎手用 searchByDate 获取最新信息
            String resultJson = searchService.searchByDate(
                    task.query(), props.getMaxResultsPerTask(), props.getFreshnessDays());
            items = parseResults(task, resultJson);
            log.info("WebSearch 采集完成 | query={} | count={}", task.query(), items.size());
        } catch (Exception e) {
            log.error("WebSearch 采集失败 | query={}", task.query(), e);
        }
        return items;
    }

    private List<InformationItem> parseResults(SearchTask task, String resultJson) {
        List<InformationItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return items;

            for (JsonNode r : results) {
                String title = safeText(r, "title");
                String content = safeText(r, "content");
                String url = safeText(r, "url");

                if (title.isEmpty() && content.isEmpty()) continue;

                InformationItem item = InformationItem.create(
                        title,
                        content,
                        url,
                        getType(),
                        task.category()
                );
                item.setPublishedAt(InformationFreshness.parsePublishedAt(
                        safeText(r, "published_date")));
                if (InformationFreshness.isFresh(item, props.getFreshnessDays())) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            log.error("WebSearch 结果解析失败", e);
        }
        return items;
    }

    private static String safeText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}
