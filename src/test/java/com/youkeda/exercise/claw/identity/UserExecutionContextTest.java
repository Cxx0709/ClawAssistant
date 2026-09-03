package com.youkeda.exercise.claw.identity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserExecutionContextTest {

    @Test
    void nestedScopesRestoreTheirPredecessor() {
        UserExecutionContext context = new UserExecutionContext();
        try (var first = context.open("alice")) {
            assertEquals("alice", context.requireUserId());
            try (var second = context.open("bob")) {
                assertEquals("bob", context.requireUserId());
            }
            assertEquals("alice", context.requireUserId());
        }
        assertNull(context.currentUserIdOrNull());
        assertThrows(IllegalStateException.class, context::requireUserId);
    }

    @Test
    void concurrentThreadsNeverShareTenantIdentity() throws Exception {
        UserExecutionContext context = new UserExecutionContext();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> alice = () -> {
                try (var ignored = context.open("alice")) { return context.requireUserId(); }
            };
            Callable<String> bob = () -> {
                try (var ignored = context.open("bob")) { return context.requireUserId(); }
            };
            assertEquals("alice", pool.submit(alice).get());
            assertEquals("bob", pool.submit(bob).get());
        } finally {
            pool.shutdownNow();
        }
        assertNull(context.currentUserIdOrNull());
    }
}
