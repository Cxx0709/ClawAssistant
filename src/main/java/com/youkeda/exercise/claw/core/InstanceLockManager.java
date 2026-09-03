package com.youkeda.exercise.claw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例进程锁管理器。
 *
 * <p>通过 OS 文件锁（{@link FileLock}）保证同一工作目录下只有一个 ClawAssistant 实例运行，
 * 避免同一数据目录被多个实例同时写入。
 *
 * <p>锁由 JVM 持有：进程正常退出、崩溃、被 kill 时 OS 都会自动释放，不会留下死锁。
 * 锁文件默认创建在运行目录下（{@value #DEFAULT_LOCK_FILE}），不写入业务数据。
 *
 * <p>用法：
 * <pre>{@code
 * InstanceLockManager lock = new InstanceLockManager();
 * if (!lock.acquireLock()) {
 *     // 已有实例运行，退出
 *     System.exit(0);
 * }
 * Runtime.getRuntime().addShutdownHook(new Thread(lock::releaseLock));
 * SpringApplication.run(...);
 * }</pre>
 */
public class InstanceLockManager {

    private static final Logger log = LoggerFactory.getLogger(InstanceLockManager.class);

    /** 默认锁文件名（相对运行目录） */
    public static final String DEFAULT_LOCK_FILE = ".clawassistant.lock";

    private final Path lockFilePath;
    private FileChannel channel;
    private FileLock lock;

    public InstanceLockManager() {
        // 可通过 -Dclaw.instance.lock=<path> 覆盖，便于多项目/多目录隔离
        this(Path.of(System.getProperty("claw.instance.lock", DEFAULT_LOCK_FILE)));
    }

    public InstanceLockManager(Path lockFilePath) {
        this.lockFilePath = lockFilePath;
    }

    /**
     * 尝试获取进程锁。
     *
     * @return true = 获取成功（本实例为唯一实例）；false = 已有实例占用锁，本实例应退出
     */
    public synchronized boolean acquireLock() {
        try {
            channel = FileChannel.open(lockFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                log.warn("已有 ClawAssistant 实例运行，当前实例退出 | lockFile={} | holder={}",
                        lockFilePath.toAbsolutePath(), instanceId());
                releaseLock();
                return false;
            }
            log.info("实例锁获取成功 | lockFile={} | instance={}",
                    lockFilePath.toAbsolutePath(), instanceId());
            return true;
        } catch (OverlappingFileLockException e) {
            // 同一 JVM 内重复获取（测试场景 / 重复调用），视为已有实例
            log.warn("已有 ClawAssistant 实例运行（同 JVM 锁冲突），当前实例退出 | lockFile={}",
                    lockFilePath.toAbsolutePath());
            releaseLock();
            return false;
        } catch (IOException e) {
            log.error("获取实例锁失败 | lockFile={} | error={}",
                    lockFilePath.toAbsolutePath(), e.getMessage());
            releaseLock();
            return false;
        }
    }

    /**
     * 释放进程锁。幂等，可安全多次调用。
     */
    public synchronized void releaseLock() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                log.warn("释放实例锁失败", e);
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                log.warn("关闭锁文件失败", e);
            }
            channel = null;
        }
    }

    /** 当前实例是否持有锁 */
    public synchronized boolean isHeld() {
        return lock != null && lock.isValid();
    }

    /**
     * 实例 ID：hostname-PID。
     * <p>用于日志中快速区分不同实例（多实例排查）。例如 {@code DESKTOP-XXX-33640}。
     */
    public static String instanceId() {
        String hostname = "unknown-host";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // 获取主机名失败时使用占位符，不影响进程锁逻辑
        }
        return hostname + "-" + ProcessHandle.current().pid();
    }
}
