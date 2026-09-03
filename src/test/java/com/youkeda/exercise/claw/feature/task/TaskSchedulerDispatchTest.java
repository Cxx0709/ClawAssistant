package com.youkeda.exercise.claw.feature.task;

import com.youkeda.exercise.claw.feature.schedule.ScheduleReminderService;
import com.youkeda.exercise.claw.feature.task.executor.AgentTaskExecutor;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import com.youkeda.exercise.claw.feature.task.scheduler.TaskSchedulerService;
import com.youkeda.exercise.claw.feature.task.service.RepeatCalculator;
import com.youkeda.exercise.claw.notification.NotificationSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 定时任务调度器单元测试
 *
 * <p>覆盖：
 * - REMINDER 任务发送文字提醒
 * - AGENT 任务调用 AgentTaskExecutor
 * - 周期任务执行后状态保持
 * - 一次性任务执行后标记 DONE
 */
@ExtendWith(MockitoExtension.class)
class TaskSchedulerDispatchTest {

    @TempDir
    File tempDir;

    private ScheduledTaskRepository taskRepository;
    private RepeatCalculator repeatCalculator;

    @Mock
    private NotificationSink notificationSink;

    @Mock
    private AgentTaskExecutor agentTaskExecutor;

    @Mock
    private ScheduleReminderService scheduleReminderService;

    private TaskSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        taskRepository = new ScheduledTaskRepository();
        setField(taskRepository, "dbPath", new File(tempDir, "test-scheduler.db").getAbsolutePath());
        taskRepository.init();
        repeatCalculator = new RepeatCalculator();

        scheduler = new TaskSchedulerService(taskRepository, notificationSink, repeatCalculator, agentTaskExecutor, scheduleReminderService);
        // 设置 running=true 让调度器的 checkAndExecute 可以执行
        setField(scheduler, "running", new java.util.concurrent.atomic.AtomicBoolean(true));
        // P0-2：调度器改为异步执行池（taskExecutor.execute(...)）。测试直接反射调用
        // checkAndExecute 而不会调 start()，故注入同步执行器保持测试的确定性
        // （command.run() 内联执行，断言立即成立，无需等待线程池）。
        setField(scheduler, "taskExecutor", syncExecutor());
    }

    /** 同步执行器：execute 立即在当前线程内联执行，用于单元测试替代真实线程池 */
    private static ExecutorService syncExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return true; }
            @Override public boolean isTerminated() { return true; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== REMINDER 任务 ====================

    @Test
    void reminderTaskShouldSendTextMessage() throws Exception {
        // 创建一个已到期的 REMINDER 任务
        ScheduledTask task = new ScheduledTask("user1", "开会提醒",
                LocalDateTime.now().minusMinutes(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_REMINDER);
        task.setNextExecuteTime(task.getExecuteTime());
        taskRepository.save(task);

        // 手动触发调度器扫描
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);

        // 验证：发送了微信消息
        verify(notificationSink).publish(eq("user1"), eq("REMINDER"), anyString(),
                contains("开会提醒"), anyInt(), isNull());
        verify(agentTaskExecutor, never()).execute(any());

        // 验证：任务已标记 DONE
        ScheduledTask found = taskRepository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_DONE, found.getStatus());
    }

    // ==================== AGENT 任务 ====================

    @Test
    void agentTaskShouldCallAgentExecutor() throws Exception {
        // 创建一个已到期的 AGENT 任务
        ScheduledTask task = new ScheduledTask("user2", "总结今天AI新闻",
                LocalDateTime.now().minusMinutes(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        taskRepository.save(task);

        // 手动触发调度器扫描
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);

        // 验证：AgentTaskExecutor 被调用
        ArgumentCaptor<ScheduledTask> taskCaptor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(agentTaskExecutor, times(1)).execute(taskCaptor.capture());
        assertEquals(task.getId(), taskCaptor.getValue().getId());
        assertEquals(ScheduledTask.TASK_TYPE_AGENT, taskCaptor.getValue().getTaskType());

        // 验证：没有发送普通文字提醒
        verify(notificationSink, never()).publish(eq("user2"), anyString(), anyString(), anyString(), anyInt(), any());

        // 验证：一次性 Agent 任务已标记 DONE
        ScheduledTask found = taskRepository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_DONE, found.getStatus());
    }

    // ==================== 混合场景 ====================

    @Test
    void shouldHandleMixedTaskTypes() throws Exception {
        // 同时创建 REMINDER 和 AGENT 任务
        ScheduledTask reminder = new ScheduledTask("user3", "喝水提醒",
                LocalDateTime.now().minusMinutes(1));
        reminder.setTaskType(ScheduledTask.TASK_TYPE_REMINDER);
        reminder.setNextExecuteTime(reminder.getExecuteTime());
        taskRepository.save(reminder);

        ScheduledTask agent = new ScheduledTask("user3", "整理今日待办",
                LocalDateTime.now().minusMinutes(1));
        agent.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        agent.setNextExecuteTime(agent.getExecuteTime());
        taskRepository.save(agent);

        // 触发调度器扫描
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);

        // 验证：两个任务都被执行
        verify(notificationSink, times(1)).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
        verify(agentTaskExecutor, times(1)).execute(any());

        // 验证：两个任务都标记为 DONE
        assertEquals(ScheduledTask.STATUS_DONE, taskRepository.findById(reminder.getId()).getStatus());
        assertEquals(ScheduledTask.STATUS_DONE, taskRepository.findById(agent.getId()).getStatus());
    }

    // ==================== 周期 Agent 任务 ====================

    @Test
    void recurringAgentTaskShouldStayActiveAfterExecution() throws Exception {
        ScheduledTask task = new ScheduledTask("user4", "每天早上总结新闻",
                LocalDateTime.now().minusMinutes(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setRepeatInterval(1);
        task.setNextExecuteTime(task.getExecuteTime());
        taskRepository.save(task);

        // 触发调度器扫描
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);

        // 验证：Agent 任务被执行
        verify(agentTaskExecutor, times(1)).execute(any());

        // 验证：周期任务保持 ACTIVE，next_execute_time 已更新
        ScheduledTask found = taskRepository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_ACTIVE, found.getStatus(),
                "周期 Agent 任务执行后应保持 ACTIVE");
        assertNotNull(found.getNextExecuteTime());
        assertTrue(found.getNextExecuteTime().isAfter(LocalDateTime.now().minusMinutes(5)),
                "next_execute_time 应更新为未来时间");
    }

    // ==================== PAUSED 任务跳过 ====================

    @Test
    void pausedTaskShouldBeSkippedByScheduler() throws Exception {
        // 创建一个已到期的 PAUSED Agent 任务
        ScheduledTask task = new ScheduledTask("user5", "暂停的任务",
                LocalDateTime.now().minusMinutes(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        task.setStatus(ScheduledTask.STATUS_PAUSED);
        taskRepository.save(task);

        // 触发调度器扫描
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);

        // 验证：即使已到期，暂停任务不会被调度器执行
        verify(agentTaskExecutor, never()).execute(any());
        verify(notificationSink, never()).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), any());

        // 验证：任务状态保持 PAUSED
        ScheduledTask found = taskRepository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_PAUSED, found.getStatus());
    }

    @Test
    void resumeShouldMakeTaskSchedulableAgain() throws Exception {
        // 创建一个已到期的暂停 Agent 任务
        ScheduledTask task = new ScheduledTask("user6", "待恢复的任务",
                LocalDateTime.now().minusMinutes(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        task.setStatus(ScheduledTask.STATUS_PAUSED);
        taskRepository.save(task);

        // 先验证暂停时不会被调度
        java.lang.reflect.Method checkAndExecute = TaskSchedulerService.class
                .getDeclaredMethod("checkAndExecute");
        checkAndExecute.setAccessible(true);
        checkAndExecute.invoke(scheduler);
        verify(agentTaskExecutor, never()).execute(any());

        // 恢复任务
        taskRepository.markResumed(task.getId(), "user6");

        // 再次触发调度器
        checkAndExecute.invoke(scheduler);

        // 验证：恢复后被调度执行
        verify(agentTaskExecutor, times(1)).execute(any());
    }
}
