package com.youkeda.exercise.claw.feature.scout.collector;

import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集器注册表
 */
@Component
public class CollectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(CollectorRegistry.class);

    private static final Set<String> ENABLED_COLLECTOR_TYPES =
            Set.of("WEB_SEARCH", "RSS", "GITHUB");

    private final Map<String, Collector> collectors = new ConcurrentHashMap<>();

    public CollectorRegistry(List<Collector> collectorList) {
        for (Collector c : collectorList) {
            if (!ENABLED_COLLECTOR_TYPES.contains(c.getType())) {
                log.info("采集器已禁用 | type={}", c.getType());
                continue;
            }
            collectors.put(c.getType(), c);
            log.info("采集器已注册 | type={}", c.getType());
        }
    }

    /**
     * 对所有搜索任务执行采集
     */
    public List<InformationItem> collectAll(List<SearchTask> tasks) {
        List<InformationItem> allItems = new ArrayList<>();
        SearchTask rssContext = null;

        for (SearchTask task : tasks) {
            if (SearchTask.JOB.equals(task.category())
                    || SearchTask.COMPETITION.equals(task.category())) {
                log.info("搜索任务等待专用非 Tavily 数据源 | category={} | query={}",
                        task.category(), task.query());
                continue;
            }

            if (rssContext == null) {
                rssContext = task;
            }

            Collector collector = SearchTask.GITHUB.equals(task.category())
                    ? collectors.get("GITHUB")
                    : collectors.get("WEB_SEARCH");
            if (collector == null && SearchTask.GITHUB.equals(task.category())) {
                collector = collectors.get("WEB_SEARCH");
            }
            if (collector == null) {
                log.warn("无可用采集器 | category={} | query={}", task.category(), task.query());
                continue;
            }

            try {
                allItems.addAll(collector.collect(task));
            } catch (Exception e) {
                log.error("采集失败 | type={} | task={}", collector.getType(), task.query(), e);
            }
        }

        Collector rss = collectors.get("RSS");
        if (rss != null && rssContext != null) {
            try {
                allItems.addAll(rss.collect(rssContext));
            } catch (Exception e) {
                log.error("RSS 采集失败", e);
            }
        }

        log.info("采集汇总 | tasks={} | items={}", tasks.size(), allItems.size());
        return allItems;
    }
}
