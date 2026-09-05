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
    // 简化工厂方法
    public static GeneratedArtifact of(String id, String userId, ArtifactKind kind,
                                        String mimeType, String fileName, long size,
                                        String description, String url) {
        return new GeneratedArtifact(id, userId, kind, mimeType, fileName, size, description, url, Instant.now());
    }
}
