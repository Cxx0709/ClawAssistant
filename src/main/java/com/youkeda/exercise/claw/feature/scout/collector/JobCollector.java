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
 * 招聘信息采集器
 *
 * 通过 WebSearch 搜索国内招聘岗位（Boss直聘、拉勾、智联等）
 */
@Component
public class JobCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(JobCollector.class);

    private final SearchService searchService;
    private final ScoutProperties props;
    private final ObjectMapper objectMapper;

    public JobCollector(SearchService searchService, ScoutProperties props, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "JOB";
    }

    @Override
    public List<InformationItem> collect(SearchTask task) {
        List<InformationItem> items = new ArrayList<>();
        try {
            String query = buildQuery(task.query());
            String resultJson = searchService.searchByDate(query, props.getMaxResultsPerTask());
            items = parseResults(task, resultJson);
            log.info("招聘采集完成 | query={} | count={}", task.query(), items.size());
        } catch (Exception e) {
            log.error("招聘采集失败 | query={}", task.query(), e);
        }
        return items;
    }

    private String buildQuery(String query) {
        // 用 site: 指定搜索国内主流招聘平台
        return "site:zhipin.com OR site:lagou.com OR site:liepin.com " + query + " 招聘";
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
                        title, content, url, getType(), task.category()
                );
                item.setPublishedAt(InformationFreshness.parsePublishedAt(
                        safeText(r, "published_date")));
                items.add(item);
            }
        } catch (Exception e) {
            log.error("招聘结果解析失败", e);
        }
        return items;
    }

    private static String safeText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}
