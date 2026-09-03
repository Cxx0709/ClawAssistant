package com.youkeda.exercise.claw.agent.memory.longterm;

import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAsyncConfigurationTest {

    @Test
    void propagatesBoundUserIdentityToMemoryWorker() throws Exception {
        UserExecutionContext identity = new UserExecutionContext();
        Executor executor = new MemoryAsyncConfiguration()
                .memoryTaskExecutor(new LongTermMemoryProperties(), identity);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> observedUserId = new AtomicReference<>();

        try (UserExecutionContext.Scope ignored = identity.open("web-user")) {
            executor.execute(() -> {
                observedUserId.set(identity.requireUserId());
                completed.countDown();
            });
        }

        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals("web-user", observedUserId.get());
        ((ThreadPoolTaskExecutor) executor).shutdown();
    }
}
