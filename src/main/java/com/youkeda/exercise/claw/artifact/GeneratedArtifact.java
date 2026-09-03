package com.youkeda.exercise.claw.artifact;

import java.time.Instant;

public record GeneratedArtifact(
        String id,
        String userId,
        ArtifactKind kind,
        String mimeType,
        String fileName,
        long size,
        String description,
        String url,
        Instant createdAt
) {
}
