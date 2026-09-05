package com.youkeda.exercise.claw.web.conversation;

public record Conversation(
        String id,
        String title,
        boolean pinned,
        boolean archived,
        String lastMessagePreview,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        String roleId) {

    public Conversation(String id, String title, boolean pinned, boolean archived,
                        String lastMessagePreview, long createdAt, long updatedAt, Long deletedAt) {
        this(id, title, pinned, archived, lastMessagePreview, createdAt, updatedAt, deletedAt, null);
    }
}
