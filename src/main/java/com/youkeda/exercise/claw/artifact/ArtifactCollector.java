package com.youkeda.exercise.claw.artifact;

import java.util.List;

public interface ArtifactCollector {

    GeneratedArtifact emit(ArtifactKind kind, byte[] content, String mimeType,
                           String fileName, String description);

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
