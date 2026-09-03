package com.youkeda.exercise.claw.web.conversation;

public record ToolTraceItem(
        String id,
        String name,
        String skill,
        String state,
        Long durationMs,
        String detail) {
}
