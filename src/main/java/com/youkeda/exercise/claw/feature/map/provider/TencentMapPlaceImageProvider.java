package com.youkeda.exercise.claw.feature.map.provider;

import com.youkeda.exercise.claw.feature.map.TencentMapClient;
import com.youkeda.exercise.claw.feature.map.TencentMapException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 腾讯地图 POI 图片 Provider
 *
 * <p>由于腾讯地图基础 API（place/v1/detail）不返回图片数据
 * （无 rich_info/pic_list 字段），且 get_rich 参数需要企业授权，
 * 本 Provider 当前返回空列表。保留此 Provider 供未来企业授权后启用。
 *
 * <p>腾讯地图仍正常服务于：
 * <ul>
 *   <li>地点搜索（searchPoi）</li>
 *   <li>路线规划（direction）</li>
 *   <li>距离计算（distanceMatrix）</li>
 * </ul>
 *
 * <p>通过 {@code place-image.provider=tencent} 启用。
 */
@Component
@ConditionalOnProperty(name = "place-image.provider", havingValue = "tencent", matchIfMissing = false)
public class TencentMapPlaceImageProvider implements PlaceImageProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentMapPlaceImageProvider.class);

    public TencentMapPlaceImageProvider(TencentMapClient mapClient) {
        log.info("TencentMapPlaceImageProvider 已加载（当前API不返回图片，需企业授权 rich_info）");
    }

    @Override
    public List<String> searchImages(String keyword, String city) {
        log.warn("TencentMapPlaceImageProvider | 图片功能不可用（需腾讯地图企业授权 get_rich） | keyword={} | city={}",
                keyword, city);
        return List.of();
    }
}