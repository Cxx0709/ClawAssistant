package com.youkeda.exercise.claw.identity;

import org.springframework.stereotype.Component;

/**
 * Carries the authenticated tenant through synchronous service calls made by one
 * agent execution. Every scope restores its predecessor, so nested executions are safe.
 */
@Component
public class UserExecutionContext {

    private final ThreadLocal<String> current = new ThreadLocal<>();

    public Scope open(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String previous = current.get();
        current.set(userId);
        return new Scope(previous);
    }

    public String requireUserId() {
        String userId = current.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前执行未绑定用户身份");
        }
        return userId;
    }

    public String currentUserIdOrNull() {
        return current.get();
    }

    public final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String previous) {
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
}
