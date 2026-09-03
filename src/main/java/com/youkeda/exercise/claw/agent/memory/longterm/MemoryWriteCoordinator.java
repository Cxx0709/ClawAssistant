package com.youkeda.exercise.claw.agent.memory.longterm;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes read-decide-write operations for the same user/topic inside one app
 * instance. Fixed stripes avoid retaining one lock per remembered topic forever.
 */
@Component
public class MemoryWriteCoordinator {

    private static final int STRIPE_COUNT = 64;
    private static final String UNRESOLVED_TOPIC = "<unresolved>";

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public MemoryWriteCoordinator() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    public <T> T withTopicLock(String topicKey, Supplier<T> operation) {
        String normalizedTopic = topicKey == null || topicKey.isBlank()
                ? UNRESOLVED_TOPIC : topicKey;
        ReentrantLock lock = stripes[Math.floorMod(normalizedTopic.hashCode(), stripes.length)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
