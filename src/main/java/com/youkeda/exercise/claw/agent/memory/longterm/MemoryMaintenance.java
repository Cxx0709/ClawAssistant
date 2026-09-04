package com.youkeda.exercise.claw.agent.memory.longterm;

import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled work must bind each tenant just like an authenticated request does. */
@Component
public class MemoryMaintenance {
    private final JdbcTemplate jdbc;
    private final UserExecutionContext context;
    private final MemoryEvictionService eviction;
    private final MemoryWriteCoordinator writes;

    public MemoryMaintenance(JdbcTemplate jdbc, UserExecutionContext context,
                             MemoryEvictionService eviction, MemoryWriteCoordinator writes) {
        this.jdbc = jdbc; this.context = context; this.eviction = eviction; this.writes = writes;
    }

    @Scheduled(cron = "${memory.eviction-cron:0 0 3 * * *}")
    public void run() {
        for (String userId : jdbc.queryForList("SELECT id FROM app_user WHERE enabled = 1", String.class)) {
            try (var ignored = context.open(userId)) {
                writes.withTopicLock("memory-write", () -> { eviction.scheduledEviction(); return null; });
            } catch (Exception e) {
                LoggerFactory.getLogger(MemoryMaintenance.class).warn("用户记忆维护失败 | userId={}", userId, e);
            }
        }
    }
}
