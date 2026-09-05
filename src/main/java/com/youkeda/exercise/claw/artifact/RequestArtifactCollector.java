package com.youkeda.exercise.claw.artifact;

import java.util.ArrayList;
import java.util.List;

public final class RequestArtifactCollector implements ArtifactCollector {

    private final ArtifactService artifactService;
    private final String userId;
    private final List<GeneratedArtifact> artifacts = new ArrayList<>();

    public RequestArtifactCollector(ArtifactService artifactService, String userId) {
        this.artifactService = artifactService;
        this.userId = userId;
    }

    @Override
    public synchronized GeneratedArtifact emit(ArtifactKind kind, byte[] content, String mimeType,
                                               String fileName, String description) {
        GeneratedArtifact artifact = artifactService.store(
                userId, kind, content, mimeType, fileName, description);
        artifacts.add(artifact);
        return artifact;
    }

    @Override
    public synchronized List<GeneratedArtifact> artifacts() {
        return List.copyOf(artifacts);
    }
}
