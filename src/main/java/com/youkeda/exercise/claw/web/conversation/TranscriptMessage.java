package com.youkeda.exercise.claw.web.conversation;

import com.youkeda.exercise.claw.artifact.GeneratedArtifact;

import java.util.List;

/** 完整、可恢复的前端可见消息。 */
public record TranscriptMessage(
        String id,
        String role,
        String content,
        List<GeneratedArtifact> attachments,
        List<GeneratedArtifact> artifacts,
        List<ToolTraceItem> tools,
        List<String> skills,
        String status,
        String runId,
        String errorText,
        Long totalMs,
        long createdAt,
        long updatedAt) {
}
