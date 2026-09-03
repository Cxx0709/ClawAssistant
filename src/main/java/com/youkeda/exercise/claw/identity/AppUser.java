package com.youkeda.exercise.claw.identity;

import java.time.Instant;

/** Application-native account. The id is an opaque tenant key, never a channel id. */
public record AppUser(
        String id,
        String username,
        String passwordHash,
        String displayName,
        boolean enabled,
        Instant createdAt
) {
}
