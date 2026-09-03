package com.youkeda.exercise.claw.feature.scout.processor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 为采集信息生成跨运行稳定的身份标识。
 *
 * <p>相同来源 URL（忽略常见跟踪参数）会得到相同标识；没有 URL 时退化为标题。
 */
public final class InformationIdentity {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "spm", "from", "ref", "source");

    private InformationIdentity() {
    }

    public static String stableKey(String source, String title) {
        String identity = canonicalSource(source);
        if (identity.isBlank()) {
            identity = "title:" + normalizeText(title);
        }
        return sha256(identity);
    }

    public static String pointUuid(InformationItem item) {
        String key = stableKey(item.getSource(), item.getTitle());
        return UUID.nameUUIDFromBytes(
                key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String canonicalSource(String source) {
        if (source == null || source.isBlank()) return "";
        String trimmed = source.strip();
        try {
            URI uri = URI.create(trimmed);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            if (scheme == null || host == null) {
                return normalizeText(trimmed);
            }

            String path = uri.getPath();
            if (path == null || path.isBlank()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = canonicalQuery(uri.getRawQuery());
            return new URI(scheme, uri.getUserInfo(), host, uri.getPort(),
                    path, query.isBlank() ? null : query, null).toASCIIString();
        } catch (Exception ignored) {
            return normalizeText(trimmed);
        }
    }

    private static String canonicalQuery(String query) {
        if (query == null || query.isBlank()) return "";
        return Arrays.stream(query.split("&"))
                .filter(part -> !part.isBlank())
                .filter(part -> {
                    String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name);
                })
                .sorted()
                .collect(Collectors.joining("&"));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成信息标识", e);
        }
    }
}
