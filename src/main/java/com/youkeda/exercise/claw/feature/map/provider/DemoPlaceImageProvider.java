package com.youkeda.exercise.claw.feature.map.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 测试图片 Provider（默认）
 *
 * <p>使用 picsum.photos 生成稳定的测试图片 URL。
 * 当 {@code place-image.provider=demo} 或未配置时生效，作为 fallback。
 *
 * <p>Seed 使用 MD5(keyword|city) 的 hex 前缀，保证 URL 安全且同一查询产出一致的图片。
 */
@Component
@ConditionalOnProperty(name = "place-image.provider", havingValue = "demo", matchIfMissing = true)
public class DemoPlaceImageProvider implements PlaceImageProvider {

    private static final Logger log = LoggerFactory.getLogger(DemoPlaceImageProvider.class);

    @Override
    public List<String> searchImages(String keyword, String city) {
        log.debug("DemoPlaceImageProvider | keyword={} | city={}", keyword, city);

        String query = (city != null && !city.isBlank() ? city : "") + "|" + keyword;
        String seed = md5Hex(query).substring(0, 8);

        return List.of(
                "https://picsum.photos/seed/" + seed + "a/800/600",
                "https://picsum.photos/seed/" + seed + "b/800/600"
        );
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available — fallback
            return Integer.toHexString(input.hashCode());
        }
    }
}