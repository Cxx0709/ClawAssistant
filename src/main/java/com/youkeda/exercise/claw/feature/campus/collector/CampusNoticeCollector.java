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

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CampusNoticeCollector {

    private static final Logger log = LoggerFactory.getLogger(CampusNoticeCollector.class);
    private static final int TIMEOUT_SECONDS = 15;

    /** 南邮教务处通知公告页 */
    public static final String NJUPT_NOTICE_URL = "https://jwc.njupt.edu.cn/1622/list34.psp";

    /**
     * 爬取通知列表，只抓 title + url + date
     *
     * @param schoolUrl 教务处通知列表页 URL
     * @return 通知列表（不含正文），source 固定为 EXAM
     */
    public List<NotificationItem> collect(String schoolUrl) {
        List<NotificationItem> items = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(schoolUrl)
                    .timeout((int) Duration.ofSeconds(TIMEOUT_SECONDS).toMillis())
                    .userAgent("ClawBot-Campus/1.0")
                    .get();

            String baseUri = doc.baseUri();
            if (baseUri == null || baseUri.isBlank()) {
                baseUri = schoolUrl;
            }

            // 南邮 Sudy CMS 列表结构：每个通知在一个 <li> 或 <div> 中
            // 标题在 class="title" 的 div 中，日期在 class="d" 的 div 中
            // 尝试多种选择器兼容
            List<Element> entries = doc.select("div.list-item, li.list-item, div.news-item");
            if (entries.isEmpty()) {
                // 南邮特有结构：直接用 title + date 选择器
                entries = doc.select("div.title").parents();
            }
            if (entries.isEmpty()) {
                entries = doc.select("div.title").stream().map(Element::parent).toList();
            }

            if (entries.isEmpty()) {
                // 兜底：直接从 title 选择器找链接
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
                            items.add(new NotificationItem("EXAM", title, url, date));
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
                        items.add(new NotificationItem("EXAM", title, url, date));
                    }
                }
            }

            log.info("通知列表采集完成 | url={} | count={}", schoolUrl, items.size());

        } catch (Exception e) {
            log.error("通知列表采集失败 | url={}", schoolUrl, e);
        }
        return items;
    }

    private String resolveUrl(String href, String baseUri) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        // 去掉 baseUri 末尾的路径部分，拼接相对路径
        String base = baseUri.replaceAll("/[^/]*$", "/");
        if (href.startsWith("/")) {
            base = baseUri.replaceAll("^(https?://[^/]+).*$", "$1");
        }
        return base + (href.startsWith("/") ? "/" + href.substring(1) : href);
    }

    private String findDate(Element entry) {
        Element dateEl = entry.selectFirst("div.d, span.date, span.time, em");
        return dateEl != null ? dateEl.text().trim() : "";
    }
}
