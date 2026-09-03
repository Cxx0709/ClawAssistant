package com.youkeda.exercise.claw.feature.scout.processor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 信息发布时间解析与时效性硬校验。
 */
public final class InformationFreshness {

    private InformationFreshness() {
    }

    public static boolean isFresh(InformationItem item, int freshnessDays) {
        return item != null && isFresh(
                item.getPublishedAt(), freshnessDays, Instant.now());
    }

    static boolean isFresh(long publishedAt, int freshnessDays, Instant now) {
        if (publishedAt <= 0) return false;
        int safeDays = Math.max(1, freshnessDays);
        Instant published = Instant.ofEpochMilli(publishedAt);
        Instant oldestAllowed = now.minusSeconds(safeDays * 24L * 3600L);
        Instant futureTolerance = now.plusSeconds(24L * 3600L);
        return !published.isBefore(oldestAllowed) && !published.isAfter(futureTolerance);
    }

    public static long parsePublishedAt(String value) {
        if (value == null || value.isBlank()) return 0L;
        String text = value.strip();
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC)
                    .toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
