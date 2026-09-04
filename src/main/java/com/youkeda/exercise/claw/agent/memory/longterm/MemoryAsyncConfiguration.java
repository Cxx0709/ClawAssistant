package com.youkeda.exercise.claw.agent.memory.longterm;

import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated bounded executor for memory extraction and persistence. */
@Configuration
public class MemoryAsyncConfiguration {

    @Bean(name = "memoryTaskExecutor", destroyMethod = "shutdown")
    public Executor memoryTaskExecutor(LongTermMemoryProperties properties,
                                       UserExecutionContext userExecutionContext) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreSize = Math.max(1, properties.getAsyncCoreSize());
        int maxSize = Math.max(coreSize, properties.getAsyncMaxSize());
        executor.setThreadNamePrefix("memory-worker-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(Math.max(1, properties.getAsyncQueueCapacity()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(task -> {
            String capturedUserId = userExecutionContext.currentUserIdOrNull();
            String capturedConversationId = userExecutionContext.currentConversationIdOrNull();
            if (capturedUserId == null) return task;
            return () -> {
                try (UserExecutionContext.Scope ignored =
                             userExecutionContext.open(capturedUserId, capturedConversationId)) {
                    task.run();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
