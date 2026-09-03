package com.youkeda.exercise.claw.feature.campus.collector;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component("campusCompetitionCollector")
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CompetitionCollector {

    private static final Logger log = LoggerFactory.getLogger(CompetitionCollector.class);
    private static final int TIMEOUT_SECONDS = 15;
    private static final String NJUPT_NOTICE_URL = "https://jwc.njupt.edu.cn/1622/list34.psp";

    /**
     * 采集通知列表，所有标题返回，由 CompetitionClassifier 筛选比赛相关
     */
    public List<NotificationItem> collect() {
        List<NotificationItem> items = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(NJUPT_NOTICE_URL)
                    .timeout((int) Duration.ofSeconds(TIMEOUT_SECONDS).toMillis())
                    .userAgent("ClawBot-Campus/1.0")
                    .get();

            String baseUri = doc.baseUri();
            if (baseUri == null || baseUri.isBlank()) baseUri = NJUPT_NOTICE_URL;

            // 与 CampusNoticeCollector 相同的解析逻辑
            List<Element> entries = doc.select("div.list-item, li.list-item, div.news-item");
            if (entries.isEmpty()) entries = doc.select("div.title").parents();
            if (entries.isEmpty()) entries = doc.select("div.title").stream().map(Element::parent).toList();

            if (entries.isEmpty()) {
                for (Element titleEl : doc.select("div.title")) {
                    Element link = titleEl.parent();
                    if (link == null) link = titleEl;
                    Element a = link.tagName().equals("a") ? link : link.selectFirst("a");
                    if (a == null) a = titleEl.selectFirst("a");
                    if (a != null) {
                        String href = a.attr("href");
                        String title = a.text().trim();
                        String url = resolveUrl(href, baseUri);
                        String date = findDate(link);
                        if (!title.isEmpty()) {
                            items.add(new NotificationItem("COMPETITION", title, url, date));
                        }
                    }
                }
            } else {
                for (Element entry : entries) {
                    Element titleEl = entry.selectFirst("div.title, span.title, a");
                    if (titleEl == null) continue;
                    Element a = titleEl.tagName().equals("a") ? titleEl : titleEl.selectFirst("a");
                    if (a == null) continue;
                    String href = a.attr("href");
                    String title = a.text().trim();
                    String url = resolveUrl(href, baseUri);
                    String date = findDate(entry);
                    if (!title.isEmpty()) {
                        items.add(new NotificationItem("COMPETITION", title, url, date));
                    }
                }
            }
            log.info("比赛采集完成 | count={}", items.size());
        } catch (Exception e) {
            log.error("比赛采集失败 | url={}", NJUPT_NOTICE_URL, e);
        }
        return items;
    }

    private String resolveUrl(String href, String baseUri) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        String base = baseUri.replaceAll("/[^/]*$", "/");
        if (href.startsWith("/")) base = baseUri.replaceAll("^(https?://[^/]+).*$", "$1");
        return base + (href.startsWith("/") ? "/" + href.substring(1) : href);
    }

    private String findDate(Element entry) {
        Element dateEl = entry.selectFirst("div.d, span.date, span.time, em");
        return dateEl != null ? dateEl.text().trim() : "";
    }
}
