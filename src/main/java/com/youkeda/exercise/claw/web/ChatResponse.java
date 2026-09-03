package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.artifact.GeneratedArtifact;

import java.util.List;

public record ChatResponse(String reply, List<GeneratedArtifact> artifacts, boolean silent) {
}
