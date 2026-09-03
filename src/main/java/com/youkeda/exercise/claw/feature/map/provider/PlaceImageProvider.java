package com.youkeda.exercise.claw.feature.map.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 地点图片 Provider 接口
 *
 * <p>定义地点图片搜索的抽象契约。不同的图片来源实现此接口：
 * <ul>
 *   <li>DemoPlaceImageProvider — 测试图片（picsum.photos）</li>
 *   <li>PexelsImageProvider — Pexels API 真实照片</li>
 *   <li>TencentMapPlaceImageProvider — 腾讯地图 POI 图片（需企业授权）</li>
 * </ul>
 *
 * <p>{@link com.youkeda.exercise.claw.feature.map.PlaceImageService} 通过此接口
 * 委托实际图片搜索，不关心图片来源。
 */
public interface PlaceImageProvider {

    /**
     * 根据关键词和城市搜索地点图片 URL
     *
     * @param keyword 地点关键词，如"西湖"、"团建基地"
     * @param city    城市名称，如"杭州"
     * @return 图片 URL 列表（可能为空，不为 null）
     * @deprecated 请使用 {@link #searchImageBytes(String, String)} 替代。
     *             返回 URL 再让下游下载的方式已废弃，图片 bytes 应在 Provider 内部获取。
     */
    @Deprecated
    default List<String> searchImages(String keyword, String city) {
        return List.of();
    }

    /**
     * 根据关键词和城市搜索地点图片并下载为字节数组
     *
     * <p>Provider 负责完整的"搜索 + 下载"流程，调用方直接拿到可用于发送的图片字节。
     * 默认实现：调用 {@link #searchImages(String, String)} 获取 URL 后逐一下载。
     *
     * @param keyword 地点关键词，如"西湖"、"团建基地"
     * @param city    城市名称，如"杭州"
     * @return 图片字节列表（可能为空，不为 null）
     */
    default List<byte[]> searchImageBytes(String keyword, String city) {
        List<String> urls = searchImages(keyword, city);
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<byte[]> result = new ArrayList<>();
        for (String url : urls) {
            try {
                byte[] bytes = downloadBytes(url);
                if (bytes != null && bytes.length > 0) {
                    result.add(bytes);
                }
            } catch (Exception e) {
                // 单张下载失败不影响其他图片
            }
        }
        return result;
    }

    /**
     * 下载 URL 内容为字节数组的默认实现。
     * 子类可覆盖此方法使用自定义下载逻辑。
     */
    default byte[] downloadBytes(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            java.net.http.HttpResponse<byte[]> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
