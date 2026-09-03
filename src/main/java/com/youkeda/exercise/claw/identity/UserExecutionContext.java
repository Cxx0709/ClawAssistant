package com.youkeda.exercise.claw.identity;

import org.springframework.stereotype.Component;

/**
 * Carries the authenticated tenant through synchronous service calls made by one
 * agent execution. Every scope restores its predecessor, so nested executions are safe.
 */
@Component
public class UserExecutionContext {

    private final ThreadLocal<ExecutionIdentity> current = new ThreadLocal<>();

    public Scope open(String userId) {
        return open(userId, null);
    }

    public Scope open(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        ExecutionIdentity previous = current.get();
        current.set(new ExecutionIdentity(userId, normalize(conversationId)));
        return new Scope(previous);
    }

    public String requireUserId() {
        String userId = currentUserIdOrNull();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前执行未绑定用户身份");
        }
        return userId;
    }

    public String currentUserIdOrNull() {
        ExecutionIdentity identity = current.get();
        return identity == null ? null : identity.userId();
    }

    public String currentConversationIdOrNull() {
        ExecutionIdentity identity = current.get();
        return identity == null ? null : identity.conversationId();
    }

    public String requireConversationId() {
        String conversationId = currentConversationIdOrNull();
        if (conversationId == null) {
            throw new IllegalStateException("当前执行未绑定对话");
        }
        return conversationId;
    }

    public final class Scope implements AutoCloseable {
        private final ExecutionIdentity previous;
        private boolean closed;

        private Scope(ExecutionIdentity previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ExecutionIdentity(String userId, String conversationId) {}
}
