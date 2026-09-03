package com.youkeda.exercise.claw.feature.map.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 带缓存的图片 Provider 装饰器
 *
 * <p>包装任意 {@link PlaceImageProvider}，对搜索结果做内存缓存。
 * 同时缓存 URL 列表和 bytes 列表，同一 (keyword, city) 组合在 TTL 内命中缓存。
 *
 * <p>线程安全，使用 {@link ConcurrentHashMap}。
 */
public class CachingPlaceImageProvider implements PlaceImageProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingPlaceImageProvider.class);

    /** 默认缓存 TTL（秒） */
    private static final long DEFAULT_TTL_SECONDS = 600;
    /** 缓存最大条目数 */
    private static final int MAX_CACHE_SIZE = 200;

    private final PlaceImageProvider delegate;
    private final long ttlSeconds;
    private final ConcurrentMap<String, CacheEntry> urlCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BytesCacheEntry> bytesCache = new ConcurrentHashMap<>();

    /**
     * @param delegate   被装饰的真实 Provider
     * @param ttlSeconds 缓存 TTL（秒）
     */
    public CachingPlaceImageProvider(PlaceImageProvider delegate, long ttlSeconds) {
        this.delegate = delegate;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    public CachingPlaceImageProvider(PlaceImageProvider delegate) {
        this(delegate, DEFAULT_TTL_SECONDS);
    }

    // ==================== URL 缓存 ====================

    @Override
    public List<String> searchImages(String keyword, String city) {
        String cacheKey = buildKey(keyword, city);
        CacheEntry cached = urlCache.get(cacheKey);

        if (cached != null && !cached.isExpired(ttlSeconds)) {
            log.info("URL缓存命中 | key={} | urls={}", cacheKey, cached.urls.size());
            return cached.urls;
        }

        List<String> urls = delegate.searchImages(keyword, city);
        evictIfNeeded(urlCache);
        urlCache.put(cacheKey, new CacheEntry(urls));
        log.info("URL缓存写入 | key={} | urls={} | ttl={}s", cacheKey, urls.size(), ttlSeconds);
        return urls;
    }

    // ==================== Bytes 缓存 ====================

    @Override
    public List<byte[]> searchImageBytes(String keyword, String city) {
        String cacheKey = buildKey(keyword, city);
        BytesCacheEntry cached = bytesCache.get(cacheKey);

        if (cached != null && !cached.isExpired(ttlSeconds)) {
            log.info("Bytes缓存命中 | key={} | count={}", cacheKey, cached.imageBytes.size());
            return cached.imageBytes;
        }

        List<byte[]> imageBytes = delegate.searchImageBytes(keyword, city);
        evictIfNeeded(bytesCache);
        bytesCache.put(cacheKey, new BytesCacheEntry(imageBytes));
        log.info("Bytes缓存写入 | key={} | count={} | ttl={}s",
                cacheKey, imageBytes.size(), ttlSeconds);
        return imageBytes;
    }

    /**
     * 清空全部缓存
     */
    public void clear() {
        int urlSize = urlCache.size();
        int bytesSize = bytesCache.size();
        urlCache.clear();
        bytesCache.clear();
        log.info("缓存已清空 | urlEntries={} | bytesEntries={}", urlSize, bytesSize);
    }

    // ==================== Internal ====================

    private static String buildKey(String keyword, String city) {
        String k = keyword != null ? keyword.trim() : "";
        String c = city != null ? city.trim() : "";
        return c + "|" + k;
    }

    private void evictIfNeeded(ConcurrentMap<?, ?> cache) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            log.info("缓存容量达到上限 {}，清空缓存", MAX_CACHE_SIZE);
            cache.clear();
        }
    }

    // ==================== Cache Entry Types ====================

    private static class CacheEntry {
        final List<String> urls;
        final Instant cachedAt;

        CacheEntry(List<String> urls) {
            this.urls = urls;
            this.cachedAt = Instant.now();
        }

        boolean isExpired(long ttlSeconds) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
    }

    private static class BytesCacheEntry {
        final List<byte[]> imageBytes;
        final Instant cachedAt;

        BytesCacheEntry(List<byte[]> imageBytes) {
            this.imageBytes = imageBytes;
            this.cachedAt = Instant.now();
        }

        boolean isExpired(long ttlSeconds) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
    }
}
