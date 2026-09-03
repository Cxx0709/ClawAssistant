package com.youkeda.exercise.claw;

import com.youkeda.exercise.claw.core.InstanceLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.Charset;

/**
 * Claw Assistant Spring Boot 入口
 *
 * 自动扫描 com.youkeda.exercise.claw 包及其子包下的所有组件
 *
 * <p>启动时通过 {@link InstanceLockManager} 获取进程锁，保证单机 SQLite 与调度器
 * 不被同一目录下的多个进程并发写入。
 */
@SpringBootApplication(scanBasePackages = "com.youkeda.exercise.claw")
@EnableScheduling
@ConfigurationPropertiesScan("com.youkeda.exercise.claw")
public class ClawAssistantApplication {

    private static final Logger log = LoggerFactory.getLogger(ClawAssistantApplication.class);

    public static void main(String[] args) {
        // 单实例保护：获取失败说明已有实例在运行，直接退出，不启动任何 Spring/Bot 逻辑
        InstanceLockManager lockManager = new InstanceLockManager();
        if (!lockManager.acquireLock()) {
            System.out.println("已有 ClawAssistant 实例运行，当前实例退出。");
            System.exit(0);
            return;
        }
        // JVM 退出（含 Ctrl+C、正常退出）时释放锁；进程被 kill 时 OS 自动释放
        Runtime.getRuntime().addShutdownHook(
                new Thread(lockManager::releaseLock, "instance-lock-release"));

        // 检测 JVM 默认编码：Windows 中文系统默认为 GBK，可能导致 ZIP 解压或文件读取乱码
        // 若当前编码非 UTF-8，建议添加 JVM 启动参数：-Dfile.encoding=UTF-8
        Charset defaultCharset = Charset.defaultCharset();
        if (!"UTF-8".equalsIgnoreCase(defaultCharset.name())) {
            System.out.println("⚠ 当前 JVM 默认编码为 " + defaultCharset.name()
                    + "，建议使用 -Dfile.encoding=UTF-8 启动以避免中文乱码");
        }
        System.out.println("Claw Assistant 启动中，默认编码: " + defaultCharset.name());

        SpringApplication.run(ClawAssistantApplication.class, args);

        log.info("ClawAssistant started. instance={}", InstanceLockManager.instanceId());
    }
}
