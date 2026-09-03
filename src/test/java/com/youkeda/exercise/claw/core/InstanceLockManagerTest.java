package com.youkeda.exercise.claw.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单实例锁测试。
 *
 * <p>模拟「两个实例」：同一 JVM 内两个 {@link InstanceLockManager} 对同一锁文件加锁。
 * 第二个 {@link #acquireLock()} 会因 {@code OverlappingFileLockException}（同 JVM 区域重叠）
 * 或 {@code tryLock()} 返回 null（跨 JVM 被占）而失败——与真实多进程场景等价。
 */
class InstanceLockManagerTest {

    private static final Path LOCK_FILE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "claw-lock-test-" + ProcessHandle.current().pid() + ".lock");

    @Test
    void firstInstanceAcquiresLock() throws Exception {
        Files.deleteIfExists(LOCK_FILE);
        InstanceLockManager first = new InstanceLockManager(LOCK_FILE);
        try {
            assertTrue(first.acquireLock(), "第一个实例应成功获取锁");
            assertTrue(first.isHeld(), "获取锁后应处于持有状态");
        } finally {
            first.releaseLock();
            Files.deleteIfExists(LOCK_FILE);
        }
    }

    @Test
    void secondInstanceCannotAcquireLockWhileFirstHoldsIt() throws Exception {
        Files.deleteIfExists(LOCK_FILE);
        InstanceLockManager first = new InstanceLockManager(LOCK_FILE);
        InstanceLockManager second = new InstanceLockManager(LOCK_FILE);
        try {
            assertTrue(first.acquireLock(), "第一个实例应成功获取锁");
            assertFalse(second.acquireLock(), "第一个实例持锁时，第二个实例应获取失败");
            assertFalse(second.isHeld(), "第二个实例不应持有锁");
        } finally {
            first.releaseLock();
            second.releaseLock();
            Files.deleteIfExists(LOCK_FILE);
        }
    }

    @Test
    void lockCanBeReacquiredAfterRelease() throws Exception {
        Files.deleteIfExists(LOCK_FILE);
        InstanceLockManager first = new InstanceLockManager(LOCK_FILE);
        InstanceLockManager second = new InstanceLockManager(LOCK_FILE);
        try {
            assertTrue(first.acquireLock());
            first.releaseLock();
            assertTrue(second.acquireLock(), "第一个实例释放后，第二个实例应能获取锁");
            assertTrue(second.isHeld());
        } finally {
            first.releaseLock();
            second.releaseLock();
            Files.deleteIfExists(LOCK_FILE);
        }
    }
}
