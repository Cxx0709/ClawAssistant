package com.youkeda.exercise.claw.feature.scout.collector;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RSS 订阅源采集器
 *
 * 解析 RSS/Atom 订阅源，提取最新条目
 */
@Component
@ConditionalOnProperty(name = "scout.rss.enabled", havingValue = "true")
public class RssCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(RssCollector.class);
    private static final int TIMEOUT_SECONDS = 15;

    private final ScoutProperties props;
    private final HttpClient httpClient;

    public RssCollector(ScoutProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getType() {
        return "RSS";
    }

    @Override
    public List<InformationItem> collect(SearchTask task) {
        List<InformationItem> allItems = new ArrayList<>();

        for (String feedUrl : props.getRss().getFeeds()) {
            try {
                List<InformationItem> items = fetchFeed(feedUrl, task);
                allItems.addAll(items);
            } catch (Exception e) {
                log.error("RSS 采集失败 | feed={}", feedUrl, e);
            }
        }

        log.info("RSS 采集完成 | feeds={} | items={}", props.getRss().getFeeds().size(), allItems.size());
        return allItems;
    }

    /**
     * 拉取并解析单个 RSS 源
     */
    private List<InformationItem> fetchFeed(String feedUrl, SearchTask task) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(feedUrl))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", "ClawBot-Scout/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("RSS 请求失败 | feed={} | status={}", feedUrl, response.statusCode());
            return List.of();
        }

        SyndFeedInput input = new SyndFeedInput();
        SyndFeed feed = input.build(new XmlReader(
                new java.io.ByteArrayInputStream(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        List<InformationItem> items = new ArrayList<>();
        String sourceName = feed.getTitle() != null ? feed.getTitle() : feedUrl;

        // RSS 发布时间通常可靠，使用独立窗口，避免 48 小时窗口导致周末或低频源无结果。
        int freshnessDays = Math.max(1, props.getRss().getFreshnessDays());
        long cutoff = System.currentTimeMillis() - freshnessDays * 24L * 3600 * 1000L;

        for (SyndEntry entry : feed.getEntries()) {
            long publishedAt = entry.getPublishedDate() != null
                    ? entry.getPublishedDate().getTime()
                    : System.currentTimeMillis();

            // 过滤过期条目
            if (publishedAt < cutoff) {
                log.debug("RSS 跳过过期条目 | title={} | published={}",
                        entry.getTitle(), entry.getPublishedDate());
                continue;
            }

            String title = entry.getTitle() != null ? entry.getTitle() : "";
            String content = entry.getDescription() != null ? entry.getDescription().getValue() : "";
            String link = entry.getLink() != null ? entry.getLink() : "";

            // 清理 HTML 标签
            content = stripHtml(content);
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }

            InformationItem item = InformationItem.create(
                    title,
                    content,
                    link,
                    getType(),
                    task.category()
            );
            item.setPublishedAt(publishedAt);
            items.add(item);
        }

        log.debug("RSS 解析完成 | feed={} | title={} | entries={}",
                feedUrl, sourceName, items.size());

        return items;
    }

    /**
     * 去除 HTML 标签
     */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
