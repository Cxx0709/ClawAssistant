package com.youkeda.exercise.claw.feature.map;

import com.youkeda.exercise.claw.feature.map.provider.CachingPlaceImageProvider;
import com.youkeda.exercise.claw.feature.map.provider.PlaceImageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 地点图片搜索服务（V3）
 *
 * <p>根据地点关键词和城市获取相关图片的完整流程：
 * <ol>
 *   <li><b>精选缓存优先</b> — 通过 {@link CuratedPlaceImageCache} 返回预配置的高质量图片</li>
 *   <li><b>POI 标准化</b> — 通过 {@link TencentMapClient#searchPoi} 获取标准化地点名称</li>
 *   <li><b>查询词翻译</b> — 通过 {@link PlaceNameTranslator} 将中文地名翻译为英文</li>
 *   <li><b>图片搜索</b> — 委托 {@link CachingPlaceImageProvider} 执行增强搜索</li>
 *   <li><b>相关性排序</b> — Pexels 端按 alt 文本与查询词的相关性排序</li>
 *   <li><b>低分截断</b> — 最高分低于阈值时返回空，避免发送错误图片</li>
 * </ol>
 *
 * <p>上层 {@link PlaceImageFunction} 调用此服务，不直接暴露给 LLM。
 */
@Service
public class PlaceImageService {

    private static final Logger log = LoggerFactory.getLogger(PlaceImageService.class);

    /** 缓存 TTL（秒）：默认 10 分钟 */
    private static final long CACHE_TTL_SECONDS = 600;

    /** 相关性阈值（从 PexelsImageProvider 同步，用于日志对照） */
    private static final double RELEVANCE_THRESHOLD = 5.0;

    private final CachingPlaceImageProvider cachingProvider;
    private final TencentMapClient tencentMapClient;
    private final PlaceNameTranslator placeNameTranslator;
    private final CuratedPlaceImageCache curatedPlaceImageCache;

    /**
     * 构造函数：接收实际图片来源，自动包装缓存；注入 POI、翻译和精选缓存依赖。
     */
    public PlaceImageService(PlaceImageProvider provider,
                             TencentMapClient tencentMapClient,
                             PlaceNameTranslator placeNameTranslator,
                             CuratedPlaceImageCache curatedPlaceImageCache) {
        this.cachingProvider = new CachingPlaceImageProvider(provider, CACHE_TTL_SECONDS);
        this.tencentMapClient = tencentMapClient;
        this.placeNameTranslator = placeNameTranslator;
        this.curatedPlaceImageCache = curatedPlaceImageCache;
        log.info("PlaceImageService V3 初始化 | delegate={} | cacheTtl={}s | curated=enabled",
                provider.getClass().getSimpleName(), CACHE_TTL_SECONDS);
    }

    /**
     * 根据关键词和城市搜索地点图片 URL
     *
     * @param keyword 地点关键词，如"西湖"、"团建基地"
     * @param city    城市名称，如"杭州"
     * @return 图片 URL 列表（可能为空）
     * @deprecated 请使用 {@link #searchImageBytes(String, String)} 替代
     */
    @Deprecated
    public List<String> searchImages(String keyword, String city) {
        log.info("地点图片搜索(URL) | keyword={} | city={}", keyword, city);
        List<String> urls = cachingProvider.searchImages(keyword, city);
        if (urls == null) {
            urls = Collections.emptyList();
        }
        log.info("地点图片搜索完成(URL) | keyword={} | city={} | urls={}", keyword, city, urls.size());
        return urls;
    }

    /**
     * 根据关键词和城市搜索地点图片并下载为字节数组（V2 优化版）
     *
     * <p>V2 改进：
     * <ul>
     *   <li>POI 标准化：通过腾讯地图获取标准地点名，消除同义词歧义</li>
     *   <li>英文翻译：将中文地点名和城市名翻译为英文，提高 Pexels 搜索准确率</li>
     *   <li>结构化日志：输出完整搜索链路，方便定位问题</li>
     * </ul>
     *
     * @param keyword 地点关键词，如"西湖"、"团建基地"
     * @param city    城市名称，如"杭州"
     * @return 图片字节列表（可能为空）
     */
    public List<byte[]> searchImageBytes(String keyword, String city) {
        log.info("地点图片搜索 | keyword={} | city={}", keyword, city);

        // ===== V3: 精选缓存优先（热门景点直接命中，跳过 POI+翻译+Pexels） =====
        List<byte[]> curated = curatedPlaceImageCache.searchImageBytes(keyword, city);
        if (!curated.isEmpty()) {
            log.info("PlaceImage: curated | keyword={} | city={} | images={}",
                    keyword, city, curated.size());
            return curated;
        }

        // ===== V2: 查询词优化 =====
        String poiName = standardizeByPoi(keyword, city);
        String translatedPlace = placeNameTranslator.translate(poiName);
        String translatedCity = placeNameTranslator.translateCity(city);

        // 用于搜索的最终关键词
        String searchKeyword = translatedPlace != null ? translatedPlace : poiName;

        if (!searchKeyword.equals(keyword) || !translatedCity.equals(city)) {
            log.info("搜索词优化 | rawKeyword={} | poi={} | placeEn={} | cityEn={}",
                    keyword, poiName, searchKeyword, translatedCity);
        }

        // ===== 委托 Provider 搜索 + 排序 + 过滤 =====
        List<byte[]> imageBytes = cachingProvider.searchImageBytes(searchKeyword, translatedCity);
        if (imageBytes == null) {
            imageBytes = Collections.emptyList();
        }

        // ===== V2: 结构化日志 =====
        String pexelsQuery = searchKeyword + " " + translatedCity;
        log.info("PlaceImage: keyword={} | poi={} | placeEn={} | cityEn={} | query={} | images={} | threshold={}",
                keyword, poiName, searchKeyword, translatedCity,
                pexelsQuery.trim(), imageBytes.size(), RELEVANCE_THRESHOLD);

        return imageBytes;
    }

    // ==================== V2 Private Helpers ====================

    /**
     * 通过腾讯地图 POI 搜索获取标准化地点名称
     *
     * <p>利用腾讯地图的中文 POI 能力，将用户的非标准关键词（如"天安门"）
     * 映射到标准 POI 名称（如"天安门广场"），消除同义词歧义。
     *
     * <p>仅在 POI 标题与原始关键词语义相关时使用，否则回退到原始关键词。
     */
    private String standardizeByPoi(String keyword, String city) {
        try {
            List<TencentMapClient.PoiResult> pois = tencentMapClient.searchPoi(keyword, city);
            if (pois != null && !pois.isEmpty()) {
                String title = pois.get(0).title();
                if (title != null && !title.isBlank()) {
                    // 仅当 POI 标题与关键词语义相关时才使用
                    // "天安门" → "天安门广场"（标题包含关键词）✓
                    // "团建基地" → "某公司名"（互不包含）✗
                    if (title.contains(keyword) || keyword.contains(title)) {
                        log.info("POI 标准化 | original={} | standard={}", keyword, title);
                        return title;
                    } else {
                        log.info("POI 标题与关键词差异过大，跳过 | keyword={} | poiTitle={}",
                                keyword, title);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("POI 标准化失败，使用原始关键词 | keyword={} | error={}",
                    keyword, e.getMessage());
        }
        return keyword;
    }
}
