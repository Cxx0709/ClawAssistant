package com.youkeda.exercise.claw.notification;

import java.time.Instant;

public record NotificationRecord(
        long id,
        String userId,
        String source,
        String title,
        String content,
        String coverUrl,
        int priority,
        String actionPayload,
        String status,
        Instant createdAt,
        Instant readAt
) {
}
