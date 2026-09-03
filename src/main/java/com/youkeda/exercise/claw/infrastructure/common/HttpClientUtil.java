package com.youkeda.exercise.claw.infrastructure.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP 客户端工具类
 * 基于 Java 21 原生 java.net.http.HttpClient
 */
public class HttpClientUtil {

    private static final int TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient;

    public HttpClientUtil() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 发送 GET 请求并返回响应体字符串
     *
     * @param url 请求 URL
     * @return 响应体内容
     * @throws Exception 网络请求异常或服务端返回非 2xx 状态码
     */
    public String doGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        checkResponseStatus(response.statusCode(), url, response.body());
        return response.body();
    }

    /**
     * 检查 HTTP 响应状态码，非 2xx 时抛出异常
     */
    private void checkResponseStatus(int statusCode, String url, String bodyPreview) throws Exception {
        if (statusCode < 200 || statusCode >= 300) {
            String preview = bodyPreview.length() > 200 ? bodyPreview.substring(0, 200) + "..." : bodyPreview;
            throw new RuntimeException("HTTP " + statusCode + " 响应异常 | url=" + url + " | body=" + preview);
        }
    }
}
