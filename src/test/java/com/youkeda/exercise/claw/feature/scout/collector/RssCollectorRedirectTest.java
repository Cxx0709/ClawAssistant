package com.youkeda.exercise.claw.feature.scout.collector;

import com.sun.net.httpserver.HttpServer;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RssCollectorRedirectTest {

    @Test
    void followsRedirectWhenFeedMovesToCanonicalUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String publishedAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC));
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Redirected Feed</title>
                    <link>https://example.test/</link>
                    <description>redirect regression fixture</description>
                    <item>
                      <title>重定向后的文章</title>
                      <link>https://example.test/article</link>
                      <description>采集成功</description>
                      <pubDate>%s</pubDate>
                    </item>
                  </channel>
                </rss>
                """.formatted(publishedAt);
        server.createContext("/feed/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/rss.xml");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/rss.xml", exchange -> {
            byte[] body = feed.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            ScoutProperties properties = new ScoutProperties();
            properties.getRss().setFeeds(List.of(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/feed/"));
            RssCollector collector = new RssCollector(properties);

            List<InformationItem> items = collector.collect(SearchTask.of(
                    "redirect", SearchTask.NEWS, "regression", 5));

            assertEquals(1, items.size());
            assertEquals("重定向后的文章", items.get(0).getTitle());
        } finally {
            server.stop(0);
        }
    }
}
