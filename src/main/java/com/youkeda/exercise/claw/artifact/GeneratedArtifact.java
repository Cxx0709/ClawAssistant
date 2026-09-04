package com.youkeda.exercise.claw.artifact;

import com.fasterxml.jackson.databind.JsonNode;

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
        Instant createdAt,
        JsonNode boardData  // BOARD类型的行程数据
) {
    // 简化工厂方法
    public static GeneratedArtifact of(String id, String userId, ArtifactKind kind,
                                        String mimeType, String fileName, long size,
                                        String description, String url) {
        return new GeneratedArtifact(id, userId, kind, mimeType, fileName, size, description, url, Instant.now(), null);
    }

    public static GeneratedArtifact board(String id, String userId, String fileName,
                                           String description, JsonNode boardData) {
        return new GeneratedArtifact(id, userId, ArtifactKind.BOARD,
                "application/x-board", fileName, 0, description, null, Instant.now(), boardData);
    }
}
