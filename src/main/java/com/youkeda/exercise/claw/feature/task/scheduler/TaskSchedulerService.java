package com.youkeda.exercise.claw.feature.task.scheduler;

import com.youkeda.exercise.claw.feature.schedule.ScheduleReminderService;
import com.youkeda.exercise.claw.feature.task.executor.AgentTaskExecutor;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import com.youkeda.exercise.claw.feature.task.service.RepeatCalculator;
import com.youkeda.exercise.claw.notification.NotificationSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务调度器
 *
 * <p>后台守护线程，定期扫描到期任务并写入站内通知。
 * 支持一次性任务和周期任务。
 *
 * <p>执行策略：
 * <ul>
 *   <li>一次性任务（ONCE）：执行后标记 DONE</li>
 *   <li>周期任务（DAILY/WEEKLY）：执行后通过 {@link RepeatCalculator} 计算下次时间，
 *       更新 next_execute_time，保持 ACTIVE 状态</li>
 * </ul>
 */
@Component
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int INITIAL_DELAY_SECONDS = 10;

    /** 课程提醒扫描间隔计数（默认每 12 次 = 约 60 秒触发一次提醒扫描） */
    @Value("${schedule.reminder.scan-interval:12}")
    private int reminderScanInterval;

    /** 周期任务连续失败上限：达到后置 FAILED 停止（默认 10 次 = 日任务 10 天） */
    @Value("${task.max-failure-count:10}")
    private int maxFailureCount;

    /** 任务执行线程池大小（默认 2；Agent 任务 LLM 耗时 10~60s，多路并发执行） */
    @Value("${task.executor-pool-size:2}")
    private int executorPoolSize;

    private final ScheduledTaskRepository taskRepository;
    private final NotificationSink notificationSink;
    private final RepeatCalculator repeatCalculator;
    private final AgentTaskExecutor agentTaskExecutor;
    private final ScheduleReminderService scheduleReminderService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    /** 任务执行线程池：只跑执行，不阻塞调度扫描（P0-2） */
    private ExecutorService taskExecutor;

    /** 扫描计数器，用于控制课程提醒的扫描频率 */
    private int scanCounter = 0;

    public TaskSchedulerService(ScheduledTaskRepository taskRepository,
                                NotificationSink notificationSink,
                                RepeatCalculator repeatCalculator,
                                AgentTaskExecutor agentTaskExecutor,
                                ScheduleReminderService scheduleReminderService) {
        this.taskRepository = taskRepository;
        this.notificationSink = notificationSink;
        this.repeatCalculator = repeatCalculator;
        this.agentTaskExecutor = agentTaskExecutor;
        this.scheduleReminderService = scheduleReminderService;
    }

    @PostConstruct
    public void start() {
        log.info("定时任务调度器启动中...");
        running.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-scheduler-thread");
            t.setDaemon(true);
            return t;
        });

        int poolSize = Math.max(1, executorPoolSize);
        taskExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "task-executor-thread");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                this::checkAndExecute,
                INITIAL_DELAY_SECONDS,
                DEFAULT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("定时任务调度器已启动 | interval={}s | initialDelay={}s | executorPool={}",
                DEFAULT_INTERVAL_SECONDS, INITIAL_DELAY_SECONDS, poolSize);
    }

    private void checkAndExecute() {
        if (!running.get()) return;

        try {
            // 1. 检查到期的定时任务
            List<ScheduledTask> dueTasks = taskRepository.findPendingAndDue();
            if (!dueTasks.isEmpty()) {
                log.info("定时任务调度器：发现 {} 个到期任务", dueTasks.size());
                for (ScheduledTask task : dueTasks) {
                    // P0-2：原子 claim，防重复提交（Agent 任务 LLM 10~60s 远超 5s 扫描间隔，
                    // 若不 claim 同一任务会在多轮扫描中被重复提交进执行池）
                    if (!taskRepository.claimForExecution(task.getId())) {
                        log.debug("任务已被其他线程 claim，跳过 | id={}", task.getId());
                        continue;
                    }
                    taskExecutor.execute(() -> safeExecuteTask(task));
                }
            }

            // 2. 定期触发课前提醒扫描（每 12 次 / 约 60 秒一次，避免过于频繁）
            scanCounter++;
            if (scanCounter >= reminderScanInterval) {
                scanCounter = 0;
                taskExecutor.execute(() -> safeCheckReminders());
            }
        } catch (Exception e) {
            log.error("定时任务调度器扫描异常", e);
        }
    }

    /** 执行池内安全执行单个任务（executeTask 内部已有 try-catch，此处兜底防池内线程被异常击穿） */
    private void safeExecuteTask(ScheduledTask task) {
        try {
            executeTask(task);
        } catch (Throwable t) {
            log.error("执行池内任务执行异常 | id={} | error={}", task.getId(), t.getMessage(), t);
        }
    }

    /** 执行池内安全执行课前提醒扫描 */
    private void safeCheckReminders() {
        try {
            scheduleReminderService.checkReminders();
        } catch (Throwable t) {
            log.error("执行池内课前提醒扫描异常", t);
        }
    }

    /**
     * 执行单个定时任务
     *
     * <p>根据任务类型分流：
     * <ul>
     *   <li>{@link ScheduledTask#TASK_TYPE_REMINDER} — 发送提醒文字</li>
     *   <li>{@link ScheduledTask#TASK_TYPE_AGENT} — 调用 AgentTaskExecutor 执行</li>
     * </ul>
     */
    private void executeTask(ScheduledTask task) {
        LocalDateTime now = LocalDateTime.now();

        log.info("执行定时任务 | id={} | userId={} | content={} | repeat={} | taskType={}",
                task.getId(), task.getUserId(), task.getContent(),
                task.getRepeatType(), task.getTaskType());

        boolean executionSuccess = false;
        try {
            if (task.isAgentTask()) {
                // Agent 任务：委托 AgentTaskExecutor
                executeAgentTask(task);
            } else {
                // 普通提醒：发送文字消息
                executeReminderTask(task);
            }
            executionSuccess = true;
        } catch (Exception e) {
            log.error("定时任务执行失败 | id={} | taskType={} | error={}",
                    task.getId(), task.getTaskType(), e.getMessage(), e);
        }

        // === 执行后处理：周期任务 vs 一次性任务 ===
        handlePostExecution(task, now, executionSuccess);
    }

    /**
     * 执行 Agent 任务
     */
    private void executeAgentTask(ScheduledTask task) throws Exception {
        agentTaskExecutor.execute(task);
    }

    /**
     * 执行普通提醒任务
     */
    private void executeReminderTask(ScheduledTask task) {
        String message = buildReminderMessage(task);
        notificationSink.publish(task.getUserId(), "REMINDER", "定时提醒",
                message, 4, null);
    }

    /**
     * 任务执行后处理：周期任务更新下次执行时间，一次性任务标记完成。
     *
     * <p>设计原则（P0-1 修正——周期任务不再"失败即永久死亡"）：
     * <ul>
     *   <li>执行成功的一次性任务 → DONE</li>
     *   <li>执行成功的周期任务 → 清零 failure_count + 计算 next_execute_time，保持 ACTIVE</li>
     *   <li>执行失败的周期任务 → failure_count+1；未达阈值时保持 ACTIVE + 计算下次时间（下次重试）；达阈值置 FAILED 停止</li>
     *   <li>执行失败的一次性任务 → 标记 FAILED</li>
     * </ul>
     */
    private void handlePostExecution(ScheduledTask task, LocalDateTime now, boolean success) {
        boolean isRecurring = repeatCalculator.hasNext(task);

        if (!success) {
            if (!isRecurring) {
                // 一次性任务：失败即终止，无重试意义
                taskRepository.markFailed(task.getId(), "任务执行失败");
                log.warn("一次性任务执行失败，已标记 FAILED | id={}", task.getId());
                return;
            }

            // 周期任务：递增失败计数
            int failureCount = taskRepository.incrementFailureCount(task.getId());
            if (failureCount >= maxFailureCount) {
                // 达阈值：停止重试，暴露在任务列表
                taskRepository.markFailed(task.getId(),
                        "周期任务连续失败 " + failureCount + " 次，已停止");
                log.error("周期任务连续失败达阈值，已停止 | id={} | failureCount={} | threshold={}",
                        task.getId(), failureCount, maxFailureCount);
                return;
            }

            // 未达阈值：保持 ACTIVE，计算下次执行时间
            log.warn("周期任务执行失败，保持 ACTIVE 下次重试 | id={} | failureCount={} | threshold={}",
                    task.getId(), failureCount, maxFailureCount);
            calculateNextTime(task, now);
            // P0-2：任务 claim 时被置 RUNNING，执行完必须归位 ACTIVE，
            // 否则 findPendingAndDue（WHERE status='ACTIVE'）再也查不到它，周期任务停摆
            taskRepository.releaseFromRunning(task.getId());
            return;
        }

        // === 执行成功 ===
        if (isRecurring) {
            // 周期任务：清零失败计数 + 计算下次执行时间，保持 ACTIVE
            taskRepository.resetFailureCount(task.getId());
            calculateNextTime(task, now);
            // P0-2：执行态释放，RUNNING → ACTIVE（同上）
            taskRepository.releaseFromRunning(task.getId());
        } else {
            // 一次性任务：标记 DONE
            taskRepository.markDone(task.getId());
            log.info("一次性任务执行完成 | id={}", task.getId());
        }
    }

    /**
     * 计算周期任务的下次执行时间并更新。
     */
    private void calculateNextTime(ScheduledTask task, LocalDateTime now) {
        try {
            LocalDateTime nextTime = repeatCalculator.calculateNext(task, now);
            if (nextTime != null) {
                taskRepository.updateNextExecuteTime(task.getId(), nextTime);
                log.info("周期任务下次执行 | id={} | nextExecuteTime={}", task.getId(), nextTime);
            } else {
                taskRepository.markDone(task.getId());
                log.warn("周期任务计算下次时间失败，已标记 DONE | id={}", task.getId());
            }
        } catch (Exception e) {
            log.error("计算下次执行时间异常 | id={}", task.getId(), e);
            taskRepository.markDone(task.getId());
        }
    }

    private String buildReminderMessage(ScheduledTask task) {
        String repeatTag = task.isRecurring() ? " 🔁" : "";
        return "⏰ 提醒：" + task.getContent() + repeatTag;
    }

    @PreDestroy
    public void stop() {
        log.info("定时任务调度器正在关闭...");
        running.set(false);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (taskExecutor != null && !taskExecutor.isShutdown()) {
            taskExecutor.shutdown();
            try {
                if (!taskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                taskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("定时任务调度器已关闭");
    }

    public int getPendingCount() {
        return taskRepository.findPendingAndDue().size();
    }
}
