package com.youkeda.exercise.claw.feature.campus.fetcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class NoticeContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(NoticeContentFetcher.class);
    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_CONTENT_LENGTH = 3000;

    /**
     * 下载通知详情页的正文内容
     *
     * @param url 通知详情页完整 URL
     * @return 正文纯文本，失败返回空字符串
     */
    public String fetch(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .timeout((int) Duration.ofSeconds(TIMEOUT_SECONDS).toMillis())
                    .userAgent("ClawBot-Campus/1.0")
                    .get();

            // 尝试多个可能的正文容器 class（各校排版不同）
            String text = trySelectText(doc, "div.content");
            if (text.isEmpty()) text = trySelectText(doc, "div.article");
            if (text.isEmpty()) text = trySelectText(doc, "div.wp_articlecontent");
            if (text.isEmpty()) text = doc.body().text();

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH);
            }
            return text.trim();

        } catch (Exception e) {
            log.warn("正文下载失败 | url={}", url, e);
            return "";
        }
    }

    private String trySelectText(Document doc, String cssQuery) {
        try {
            Element el = doc.selectFirst(cssQuery);
            return el != null ? el.text().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
