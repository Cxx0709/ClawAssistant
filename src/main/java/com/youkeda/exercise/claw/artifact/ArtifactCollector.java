package com.youkeda.exercise.claw.artifact;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface ArtifactCollector {

    GeneratedArtifact emit(ArtifactKind kind, byte[] content, String mimeType,
                           String fileName, String description);

    /**
     * 发出BOARD类型的artifact（行程看板等结构化数据）
     */
    default GeneratedArtifact emitBoard(String fileName, String description, JsonNode boardData) {
        return null; // 默认实现，子类可覆盖
    }

    List<GeneratedArtifact> artifacts();

    static ArtifactCollector noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final ArtifactCollector INSTANCE = new ArtifactCollector() {
            @Override
            public GeneratedArtifact emit(ArtifactKind kind, byte[] content, String mimeType,
                                          String fileName, String description) {
                return null;
            }

            @Override
            public List<GeneratedArtifact> artifacts() {
                return List.of();
            }
        };
        private NoopHolder() {}
    }
}
