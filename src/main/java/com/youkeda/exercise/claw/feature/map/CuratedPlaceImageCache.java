package com.youkeda.exercise.claw.feature.map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热门景点精选图片缓存（V3 — 本地资源版）
 *
 * <p>为热门景点预配置经过人工验证的本地图片，
 * 确保热门景点返回的图片准确、高质量。
 * 图片文件存放在 {@code classpath:place-images/} 目录下。
 *
 * <p>搜索优先级：
 * <ol>
 *   <li><b>精选缓存</b> — 预配置的热门景点本地图片，准确率 100%</li>
 *   <li><b>Pexels 实时搜索</b> — 非热门景点走 V2 标准化 + 翻译 + 评分流程</li>
 * </ol>
 *
 * <p>匹配策略：
 * <ul>
 *   <li>用户输入的 keyword 与景点别名列表中的任一名称匹配（双向包含关系）</li>
 *   <li>城市名匹配（可选，某些景点不限城市）</li>
 *   <li>命中后返回预加载到内存的图片字节</li>
 * </ul>
 */
@Component
public class CuratedPlaceImageCache {

    private static final Logger log = LoggerFactory.getLogger(CuratedPlaceImageCache.class);

    /** 每个景点最多返回图片数 */
    private static final int MAX_IMAGES = 3;

    /** 资源基路径 */
    private static final String RESOURCE_BASE = "classpath:place-images/";

    /** 图片文件名模板 */
    private static final List<String> IMAGE_FILE_NAMES = List.of("1.jpg", "2.jpg", "3.jpg");

    /** 精选景点条目列表 */
    private static final List<CuratedEntry> ENTRIES = buildEntries();

    /**
     * 图片字节缓存：entryKey → (bytes, cachedAt)
     * entryKey 格式: "displayName|city"
     * 启动时预加载，不使用 TTL；实例生命周期内始终有效
     */
    private final Map<String, List<byte[]>> imageCache = new ConcurrentHashMap<>();

    private final ResourceLoader resourceLoader;

    /** 初始加载的图片总数（仅用于启动日志） */
    private int totalLoadedImages;

    public CuratedPlaceImageCache(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 启动时预加载所有精选景点图片
     *
     * <p>图片加载失败不会阻止应用启动，只记录警告日志。
     * 运行时未命中缓存的条目会尝试再次加载（容错兜底）。
     */
    @PostConstruct
    public void init() {
        log.info("CuratedPlaceImageCache 初始化中 | entries={}", ENTRIES.size());

        int totalLoaded = 0;
        int totalExpected = 0;
        for (CuratedEntry entry : ENTRIES) {
            List<byte[]> images = loadImages(entry);
            String cacheKey = buildCacheKey(entry);
            if (!images.isEmpty()) {
                imageCache.put(cacheKey, images);
                totalLoaded += images.size();
            }
            totalExpected += IMAGE_FILE_NAMES.size();
            log.debug("精选图片预加载 | entry={} | dir={} | loaded={}/{}",
                    entry.displayName, entry.resourceDir,
                    images.size(), IMAGE_FILE_NAMES.size());
        }

        this.totalLoadedImages = totalLoaded;
        log.info("CuratedPlaceImageCache initialized: entries={} loadedImages={}/{}",
                ENTRIES.size(), totalLoaded, totalExpected);
    }

    /**
     * 搜索精选图片
     *
     * @param keyword 用户输入的地点关键词
     * @param city    城市名称（可能为空）
     * @return 图片字节列表（未命中时返回空列表）
     */
    public List<byte[]> searchImageBytes(String keyword, String city) {
        if (keyword == null || keyword.isBlank()) return List.of();

        // Step 1: 匹配景点条目
        CuratedEntry entry = findMatch(keyword.trim(), city);
        if (entry == null) return List.of();

        String cacheKey = buildCacheKey(entry);
        log.info("精选缓存匹配 | keyword={} | city={} | entry={}",
                keyword, city, entry.displayName);

        // Step 2: 查内存缓存
        List<byte[]> cached = imageCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // Step 3: 缓存未命中时尝试即时加载（启动时加载失败的容错）
        log.warn("精选缓存未命中，尝试即时加载 | entry={} | dir={}",
                entry.displayName, entry.resourceDir);
        List<byte[]> images = loadImages(entry);
        if (!images.isEmpty()) {
            imageCache.put(cacheKey, images);
            return images;
        }

        return List.of();
    }

    // ==================== 本地图片加载 ====================

    /**
     * 从 classpath 加载景点目录下的所有图片
     *
     * <p>目录结构：{@code place-images/{resourceDir}/1.jpg, 2.jpg, 3.jpg}
     */
    private List<byte[]> loadImages(CuratedEntry entry) {
        List<byte[]> results = new ArrayList<>();
        for (String fileName : IMAGE_FILE_NAMES) {
            if (results.size() >= MAX_IMAGES) break;
            String resourcePath = RESOURCE_BASE + entry.resourceDir + "/" + fileName;
            try {
                Resource resource = resourceLoader.getResource(resourcePath);
                if (resource.exists() && resource.isReadable()) {
                    byte[] bytes = resource.getContentAsByteArray();
                    if (bytes.length > 0) {
                        results.add(bytes);
                    }
                } else {
                    log.debug("精选图片不存在 | path={}", resourcePath);
                }
            } catch (IOException e) {
                log.debug("精选图片读取失败 | path={} | error={}", resourcePath, e.getMessage());
            }
        }
        return results;
    }

    // ==================== 匹配逻辑 ====================

    /**
     * 在精选条目中查找匹配项
     *
     * <p>匹配规则：
     * <ul>
     *   <li>关键字匹配：用户输入与条目的任一别名形成包含关系（双向）</li>
     *   <li>城市匹配：用户输入的城市与条目的城市列表匹配（条目城市列表为空时匹配任意城市）</li>
     * </ul>
     */
    private CuratedEntry findMatch(String keyword, String city) {
        for (CuratedEntry entry : ENTRIES) {
            // 关键字匹配（双向包含）
            boolean kwMatch = entry.matchKeywords.stream()
                    .anyMatch(kw -> keyword.contains(kw) || kw.contains(keyword));
            if (!kwMatch) continue;

            // 城市匹配
            if (!entry.cityNames.isEmpty()) {
                boolean cityMatch = entry.cityNames.stream()
                        .anyMatch(c -> c.isBlank()
                                || city != null && (city.contains(c) || c.contains(city)));
                if (!cityMatch) continue;
            }

            return entry;
        }
        return null;
    }

    // ==================== 内部方法 ====================

    private static String buildCacheKey(CuratedEntry entry) {
        return entry.displayName;
    }

    // ==================== 精选景点配置 ====================

    private static List<CuratedEntry> buildEntries() {
        return List.of(
                new CuratedEntry("天安门广场",
                        List.of("天安门", "天安门广场", "天安门城楼"),
                        List.of("北京"),
                        "tiananmen"),

                new CuratedEntry("故宫博物院",
                        List.of("故宫", "故宫博物院", "紫禁城", "故宫博物馆"),
                        List.of("北京"),
                        "forbidden_city"),

                new CuratedEntry("西湖",
                        List.of("西湖", "西子湖"),
                        List.of("杭州"),
                        "west_lake"),

                new CuratedEntry("外滩",
                        List.of("外滩", "外滩万国建筑博览群"),
                        List.of("上海"),
                        "bund"),

                new CuratedEntry("万里长城",
                        List.of("长城", "万里长城", "八达岭长城", "长城八达岭"),
                        List.of("北京"),
                        "great_wall"),

                new CuratedEntry("灵隐寺",
                        List.of("灵隐寺", "灵隐禅寺"),
                        List.of("杭州"),
                        "lingyin_temple")
        );
    }

    // ==================== 内部类型 ====================

    /**
     * 精选景点条目
     *
     * @param displayName  展示名称
     * @param matchKeywords 匹配关键词列表
     * @param cityNames     关联城市列表
     * @param resourceDir   资源目录名（相对于 {@code place-images/}）
     */
    private record CuratedEntry(
            String displayName,
            List<String> matchKeywords,
            List<String> cityNames,
            String resourceDir
    ) {}
}
