package com.youkeda.exercise.claw.feature.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import com.youkeda.exercise.claw.tool.task.CreateScheduleTaskTool;
import com.youkeda.exercise.claw.feature.task.service.RepeatCalculator;
import com.youkeda.exercise.claw.feature.task.service.TaskCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时任务模块单元测试
 *
 * <p>覆盖：
 * - 创建一次性任务（相对时间 + 绝对时间）
 * - 创建周期任务（DAILY + WEEKLY）
 * - 查询任务
 * - 取消任务
 * - 修改任务
 * - RepeatCalculator 策略
 * - 周期任务执行状态保持
 */
class ScheduledTaskTest {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TEST_USER = "test_user";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledTaskRepository repository;
    private TaskCreator taskCreator;
    private CreateScheduleTaskTool createFunction;

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        // 使用临时目录的独立数据库
        repository = new ScheduledTaskRepository();
        // 通过反射设 dbPath
        setField(repository, "dbPath", new File(tempDir, "test-tasks.db").getAbsolutePath());
        repository.init();
        taskCreator = new TaskCreator(repository);
        createFunction = new CreateScheduleTaskTool(MAPPER, null, taskCreator);
    }

    // ==================== 工具方法 ====================

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String userId(String name) {
        return name;
    }

    // ==================== 辅助：创建任务 ====================

    private ScheduledTask createOneTimeTask(String userId, String content, int delayMinutes) {
        ScheduledTask task = createOneTimeTaskAbsolute(userId, content, LocalDateTime.now().plusMinutes(delayMinutes));
        return task;
    }

    private ScheduledTask createOneTimeTaskAbsolute(String userId, String content, LocalDateTime executeTime) {
        ScheduledTask task = new ScheduledTask(userId, content, executeTime);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_ONCE);
        task.setNextExecuteTime(executeTime);
        return repository.save(task);
    }

    private ScheduledTask createRecurringTask(String userId, String content, LocalDateTime firstTime, String repeatType) {
        ScheduledTask task = new ScheduledTask(userId, content, firstTime);
        task.setRepeatType(repeatType);
        task.setNextExecuteTime(firstTime);
        return repository.save(task);
    }

    // ==================== 创建一次性任务 ====================

    @Test
    void shouldCreateOneTimeTaskWithDelay() {
        String content = "提交代码";
        int delayMinutes = 10;

        LocalDateTime before = LocalDateTime.now();
        ScheduledTask task = createOneTimeTask(TEST_USER, content, delayMinutes);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(task.getId(), "任务应该有 ID");
        assertEquals(TEST_USER, task.getUserId());
        assertEquals(content, task.getContent());
        assertEquals(ScheduledTask.REPEAT_TYPE_ONCE, task.getRepeatType());
        assertEquals(ScheduledTask.STATUS_ACTIVE, task.getStatus());

        // 执行时间应该在 now 到 now+delay 之间
        assertTrue(task.getExecuteTime().isAfter(before.minusSeconds(1)),
                "执行时间应该在未来");
        assertTrue(task.getExecuteTime().isBefore(after.plusMinutes(delayMinutes).plusSeconds(1)),
                "执行时间应该在延迟范围内");

        // next_execute_time = execute_time（首次）
        assertEquals(task.getExecuteTime(), task.getNextExecuteTime(),
                "首次执行时间应与 next_execute_time 一致");
    }

    @Test
    void shouldCreateOneTimeTaskWithAbsoluteTime() {
        String content = "开会";
        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);

        ScheduledTask task = createOneTimeTaskAbsolute(TEST_USER, content, futureTime);

        assertNotNull(task.getId());
        assertEquals(content, task.getContent());
        assertEquals(ScheduledTask.REPEAT_TYPE_ONCE, task.getRepeatType());
        assertEquals(ScheduledTask.STATUS_ACTIVE, task.getStatus());
        assertEquals(futureTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                task.getExecuteTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertEquals(task.getExecuteTime(), task.getNextExecuteTime());
    }

    // ==================== 创建周期任务 ====================

    @Test
    void shouldCreateDailyRecurringTask() {
        String content = "喝水";
        LocalDateTime firstTime = LocalDateTime.now().plusMinutes(30);

        ScheduledTask task = createRecurringTask(TEST_USER, content, firstTime, ScheduledTask.REPEAT_TYPE_DAILY);

        assertNotNull(task.getId());
        assertEquals(content, task.getContent());
        assertEquals(ScheduledTask.REPEAT_TYPE_DAILY, task.getRepeatType());
        assertEquals(1, task.getRepeatInterval());
        assertEquals(ScheduledTask.STATUS_ACTIVE, task.getStatus());

        // 周期任务应标记为可重复
        assertTrue(task.isRecurring(), "周期任务 isRecurring 应为 true");
        assertFalse(task.isOnce(), "周期任务 isOnce 应为 false");
    }

    @Test
    void shouldCreateWeeklyRecurringTask() {
        String content = "写周报";
        LocalDateTime nextMonday = LocalDateTime.now().plusDays(3);

        ScheduledTask task = createRecurringTask(TEST_USER, content, nextMonday, ScheduledTask.REPEAT_TYPE_WEEKLY);

        assertNotNull(task.getId());
        assertEquals(ScheduledTask.REPEAT_TYPE_WEEKLY, task.getRepeatType());
        assertTrue(task.isRecurring());
    }

    // ==================== 查询任务 ====================

    @Test
    void shouldListUserTasks() {
        createOneTimeTask(TEST_USER, "任务1", 10);
        createOneTimeTask(TEST_USER, "任务2", 20);
        createRecurringTask(TEST_USER, "周期任务", LocalDateTime.now().plusHours(1), ScheduledTask.REPEAT_TYPE_DAILY);

        List<ScheduledTask> tasks = repository.findByUserId(TEST_USER);
        assertEquals(3, tasks.size());
    }

    @Test
    void shouldListUserTasksByStatus() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "待执行任务", 10);
        repository.markDone(task.getId());

        List<ScheduledTask> activeTasks = repository.findByUserIdAndStatus(TEST_USER, ScheduledTask.STATUS_ACTIVE);
        List<ScheduledTask> doneTasks = repository.findByUserIdAndStatus(TEST_USER, ScheduledTask.STATUS_DONE);

        assertEquals(0, activeTasks.size(), "没有 ACTIVE 任务");
        assertEquals(1, doneTasks.size(), "有一个 DONE 任务");
    }

    @Test
    void shouldNotSeeOtherUsersTasks() {
        createOneTimeTask("user_a", "A的任务", 10);
        createOneTimeTask("user_b", "B的任务", 20);

        List<ScheduledTask> userBTasks = repository.findByUserId("user_b");
        assertEquals(1, userBTasks.size());
        assertEquals("B的任务", userBTasks.get(0).getContent());
    }

    // ==================== 取消任务 ====================

    @Test
    void shouldCancelActiveTask() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "要取消的任务", 10);
        assertEquals(ScheduledTask.STATUS_ACTIVE, task.getStatus());

        boolean cancelled = repository.markCancelled(task.getId(), TEST_USER);
        assertTrue(cancelled);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_CANCELLED, found.getStatus());
    }

    @Test
    void shouldNotCancelOtherUsersTask() {
        ScheduledTask task = createOneTimeTask("other_user", "别人的任务", 10);

        boolean cancelled = repository.markCancelled(task.getId(), TEST_USER);
        assertFalse(cancelled, "不能取消别人的任务");

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_ACTIVE, found.getStatus(), "任务应该还是 ACTIVE");
    }

    // ==================== 修改任务 ====================

    @Test
    void shouldUpdateActiveTask() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "原内容", 30);

        // 修改内容和时间
        String newContent = "新内容";
        LocalDateTime newTime = LocalDateTime.now().plusHours(3);
        task.setContent(newContent);
        task.setExecuteTime(newTime);
        task.setNextExecuteTime(newTime);

        boolean updated = repository.updateTask(task);
        assertTrue(updated);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(newContent, found.getContent());
        // 时间对比精确到秒
        assertEquals(newTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                found.getExecuteTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertEquals(ScheduledTask.REPEAT_TYPE_ONCE, found.getRepeatType());
    }

    @Test
    void shouldUpdateTaskRepeatType() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "改为周期任务", 30);

        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        boolean updated = repository.updateTask(task);
        assertTrue(updated);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.REPEAT_TYPE_DAILY, found.getRepeatType());
        assertTrue(found.isRecurring());
    }

    @Test
    void shouldNotUpdateDoneTaskStatusByUpdateMethod() {
        // 这个测试验证：已 DONE 的任务即便调了 updateTask（业务层应拦截），
        // 业务层应该自行检查状态。这里只验证 updateTask 本身可以写入
        //（SQL 层面没有 status 检查——业务逻辑在 UpdateScheduleTaskFunction 中）
        ScheduledTask task = createOneTimeTask(TEST_USER, "测试", 10);
        repository.markDone(task.getId());

        task.setContent("尝试修改");
        boolean updated = repository.updateTask(task);
        // SQL 层面允许更新（没有 status 过滤，业务层应拦截）
        assertTrue(updated, "Repository 层 updateTask 不检查状态，仅验证 SQL 可执行");
    }

    // ==================== RepeatCalculator 测试 ====================

    @Test
    void shouldCalculateNextForDailyTask() {
        RepeatCalculator calculator = new RepeatCalculator();

        LocalDateTime lastExecute = LocalDateTime.of(2026, 7, 28, 8, 0, 0);
        ScheduledTask dailyTask = new ScheduledTask(TEST_USER, "喝水", lastExecute);
        dailyTask.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        dailyTask.setRepeatInterval(1);

        LocalDateTime next = calculator.calculateNext(dailyTask, lastExecute);
        assertNotNull(next);
        assertEquals(LocalDateTime.of(2026, 7, 29, 8, 0, 0), next, "每日任务应加 1 天");
    }

    @Test
    void shouldCalculateNextForWeeklyTask() {
        RepeatCalculator calculator = new RepeatCalculator();

        LocalDateTime lastExecute = LocalDateTime.of(2026, 7, 28, 9, 0, 0);
        ScheduledTask weeklyTask = new ScheduledTask(TEST_USER, "写周报", lastExecute);
        weeklyTask.setRepeatType(ScheduledTask.REPEAT_TYPE_WEEKLY);
        weeklyTask.setRepeatInterval(1);

        LocalDateTime next = calculator.calculateNext(weeklyTask, lastExecute);
        assertNotNull(next);
        assertEquals(LocalDateTime.of(2026, 8, 4, 9, 0, 0), next, "每周任务应加 7 天");
    }

    @Test
    void shouldReturnNullForOnceTask() {
        RepeatCalculator calculator = new RepeatCalculator();

        LocalDateTime lastExecute = LocalDateTime.now();
        ScheduledTask onceTask = new ScheduledTask(TEST_USER, "一次性任务", lastExecute);
        onceTask.setRepeatType(ScheduledTask.REPEAT_TYPE_ONCE);

        LocalDateTime next = calculator.calculateNext(onceTask, lastExecute);
        assertNull(next, "一次性任务不计算下次执行时间");
    }

    @Test
    void hasNextShouldReturnTrueForRecurring() {
        RepeatCalculator calculator = new RepeatCalculator();

        ScheduledTask dailyTask = new ScheduledTask(TEST_USER, "喝水", LocalDateTime.now());
        dailyTask.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        assertTrue(calculator.hasNext(dailyTask));

        ScheduledTask onceTask = new ScheduledTask(TEST_USER, "一次", LocalDateTime.now());
        onceTask.setRepeatType(ScheduledTask.REPEAT_TYPE_ONCE);
        assertFalse(calculator.hasNext(onceTask));
    }

    // ==================== 周期任务执行后状态保持 ====================

    @Test
    void recurringTaskShouldStayActiveAfterExecution() {
        LocalDateTime now = LocalDateTime.now();
        ScheduledTask task = createRecurringTask(TEST_USER, "每天提醒",
                now.plusMinutes(1), ScheduledTask.REPEAT_TYPE_DAILY);

        // 模拟执行：任务仍应保持 ACTIVE，更新 next_execute_time
        assertTrue(task.isActive(), "周期任务执行前是 ACTIVE");

        // 执行后计算下次时间
        RepeatCalculator calculator = new RepeatCalculator();
        LocalDateTime nextTime = calculator.calculateNext(task, now);
        assertNotNull(nextTime);
        assertTrue(nextTime.isAfter(now), "下次执行时间应在当前之后");

        // 更新 next_execute_time
        repository.updateNextExecuteTime(task.getId(), nextTime);

        ScheduledTask afterExecution = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_ACTIVE, afterExecution.getStatus(), "周期任务执行后仍为 ACTIVE");
        assertEquals(nextTime.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                afterExecution.getNextExecuteTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), "next_execute_time 已更新");
    }

    @Test
    void oneTimeTaskShouldBecomeDoneAfterExecution() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "一次性提醒", 1);

        repository.markDone(task.getId());

        ScheduledTask after = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_DONE, after.getStatus(), "一次性任务执行后变为 DONE");
    }

    // ==================== 到期查询 ====================

    @Test
    void findPendingAndDueShouldReturnDueTasks() {
        // 创建一个已到期的任务
        createOneTimeTaskAbsolute(TEST_USER, "过期任务", LocalDateTime.now().minusMinutes(5));

        // 创建一个未到期的任务
        createOneTimeTask(TEST_USER, "未来任务", 60);

        List<ScheduledTask> dueTasks = repository.findPendingAndDue();
        assertEquals(1, dueTasks.size(), "应该只返回已到期的任务");
        assertEquals("过期任务", dueTasks.get(0).getContent());
    }

    @Test
    void findPendingAndDueShouldIncludeRecurringDueTasks() {
        // 创建一个已到期的周期任务
        createRecurringTask(TEST_USER, "每日提醒",
                LocalDateTime.now().minusHours(1), ScheduledTask.REPEAT_TYPE_DAILY);

        List<ScheduledTask> dueTasks = repository.findPendingAndDue();
        assertEquals(1, dueTasks.size());
        assertEquals("每日提醒", dueTasks.get(0).getContent());
    }

    // ==================== 任务类型（task_type）测试 ====================

    @Test
    void defaultTaskTypeShouldBeReminder() {
        ScheduledTask task = createOneTimeTask(TEST_USER, "普通提醒", 10);
        assertEquals(ScheduledTask.TASK_TYPE_REMINDER, task.getTaskType(),
                "默认 task_type 应为 REMINDER");
        assertTrue(task.isReminderTask(), "默认应为提醒任务");
        assertFalse(task.isAgentTask(), "默认不应是 Agent 任务");
    }

    @Test
    void shouldCreateAgentTask() {
        ScheduledTask task = new ScheduledTask(TEST_USER, "总结AI新闻",
                LocalDateTime.now().plusMinutes(30));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        assertNotNull(task.getId());
        assertEquals(ScheduledTask.TASK_TYPE_AGENT, task.getTaskType(), "task_type 应为 AGENT");
        assertTrue(task.isAgentTask(), "isAgentTask 应返回 true");
        assertFalse(task.isReminderTask(), "isReminderTask 应返回 false");
        assertEquals("Agent 任务", task.getTaskTypeDisplay(), "展示名应为 Agent 任务");

        // 重新从数据库读取后验证持久化
        ScheduledTask found = repository.findById(task.getId());
        assertNotNull(found);
        assertEquals(ScheduledTask.TASK_TYPE_AGENT, found.getTaskType(), "数据库持久化的 task_type 应为 AGENT");
    }

    @Test
    void agentTaskShouldSupportRecurring() {
        ScheduledTask task = new ScheduledTask(TEST_USER, "每天总结新闻",
                LocalDateTime.now().minusMinutes(5));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setRepeatInterval(1);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        assertTrue(task.isAgentTask(), "应为 Agent 任务");
        assertTrue(task.isRecurring(), "应为周期任务");
        assertEquals(ScheduledTask.REPEAT_TYPE_DAILY, task.getRepeatType());

        // 查询到期任务（已过期 5 分钟，应被查到）
        List<ScheduledTask> due = repository.findPendingAndDue();
        boolean found = due.stream().anyMatch(t -> t.getId().equals(task.getId()));
        assertTrue(found, "到期的周期 Agent 任务应出现在到期查询结果中");
    }

    @Test
    void taskTypeShouldBePersistedInDatabase() {
        // 创建 Agent 任务
        ScheduledTask agentTask = new ScheduledTask(TEST_USER, "Agent 任务",
                LocalDateTime.now().plusMinutes(10));
        agentTask.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        agentTask.setNextExecuteTime(agentTask.getExecuteTime());
        repository.save(agentTask);

        // 创建普通提醒
        ScheduledTask reminderTask = new ScheduledTask(TEST_USER, "普通提醒",
                LocalDateTime.now().plusMinutes(20));
        reminderTask.setTaskType(ScheduledTask.TASK_TYPE_REMINDER);
        reminderTask.setNextExecuteTime(reminderTask.getExecuteTime());
        repository.save(reminderTask);

        // 从数据库重新读取
        ScheduledTask foundAgent = repository.findById(agentTask.getId());
        ScheduledTask foundReminder = repository.findById(reminderTask.getId());

        assertEquals(ScheduledTask.TASK_TYPE_AGENT, foundAgent.getTaskType());
        assertEquals(ScheduledTask.TASK_TYPE_REMINDER, foundReminder.getTaskType());

        // 列表中应同时包含两种类型
        List<ScheduledTask> allTasks = repository.findByUserId(TEST_USER);
        assertEquals(2, allTasks.size());
    }

    @Test
    void repositoryShouldDefaultTaskTypeToReminderForNull() {
        // 验证：不设置 taskType 时，getTaskType() 返回 REMINDER
        ScheduledTask task = new ScheduledTask(TEST_USER, "测试", LocalDateTime.now().plusMinutes(5));
        task.setNextExecuteTime(task.getExecuteTime());
        // 不调用 setTaskType
        repository.save(task);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.TASK_TYPE_REMINDER, found.getTaskType(),
                "未设置 task_type 的任务持久化后应默认为 REMINDER");
    }

    // ==================== 暂停/恢复（PAUSED）测试 ====================

    @Test
    void shouldPauseActiveAgentTask() {
        ScheduledTask task = new ScheduledTask(TEST_USER, "每日新闻总结",
                LocalDateTime.now().plusHours(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        assertTrue(task.isActive(), "初始应为 ACTIVE");
        assertFalse(task.isPaused(), "初始不应是 PAUSED");

        // 执行暂停
        repository.markPaused(task.getId(), TEST_USER);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_PAUSED, found.getStatus(), "暂停后应为 PAUSED");
        assertTrue(found.isPaused(), "isPaused 应返回 true");
        assertFalse(found.isActive(), "暂停后不应是 ACTIVE");
    }

    @Test
    void shouldResumePausedAgentTask() {
        ScheduledTask task = new ScheduledTask(TEST_USER, "AI 摘要任务",
                LocalDateTime.now().plusHours(2));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        // 暂停
        repository.markPaused(task.getId(), TEST_USER);
        assertEquals(ScheduledTask.STATUS_PAUSED, repository.findById(task.getId()).getStatus());

        // 恢复
        repository.markResumed(task.getId(), TEST_USER);

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_ACTIVE, found.getStatus(), "恢复后应为 ACTIVE");
        assertTrue(found.isActive(), "恢复后 isActive 应返回 true");
        assertFalse(found.isPaused(), "恢复后不应是 PAUSED");
    }

    @Test
    void shouldNotPauseOtherUsersTask() {
        ScheduledTask task = new ScheduledTask("other_user", "别人的 Agent",
                LocalDateTime.now().plusHours(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        // 尝试以 TEST_USER 暂停别人的任务
        boolean paused = repository.markPaused(task.getId(), TEST_USER);
        assertFalse(paused, "不能暂停别人的任务");

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_ACTIVE, found.getStatus(), "任务应保持 ACTIVE");
    }

    @Test
    void shouldNotResumeOtherUsersTask() {
        ScheduledTask task = new ScheduledTask("other_user", "别人的 Agent",
                LocalDateTime.now().plusHours(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        // 先暂停（作为 owner）
        repository.markPaused(task.getId(), "other_user");
        assertEquals(ScheduledTask.STATUS_PAUSED, repository.findById(task.getId()).getStatus());

        // 尝试以 TEST_USER 恢复
        boolean resumed = repository.markResumed(task.getId(), TEST_USER);
        assertFalse(resumed, "不能恢复别人的任务");

        ScheduledTask found = repository.findById(task.getId());
        assertEquals(ScheduledTask.STATUS_PAUSED, found.getStatus(), "任务应保持 PAUSED");
    }

    @Test
    void pausedTaskShouldNotAppearInPendingAndDue() {
        // 创建已到期的 Agent 任务
        ScheduledTask task = new ScheduledTask(TEST_USER, "过期 Agent",
                LocalDateTime.now().minusMinutes(10));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        // 暂停它
        repository.markPaused(task.getId(), TEST_USER);

        // 查询到期任务（不应包含已暂停的）
        List<ScheduledTask> dueTasks = repository.findPendingAndDue();
        boolean found = dueTasks.stream().anyMatch(t -> t.getId().equals(task.getId()));
        assertFalse(found, "已暂停的任务不应出现在到期查询结果中");
    }

    @Test
    void shouldFindAgentTaskByType() {
        ScheduledTask agentTask = new ScheduledTask(TEST_USER, "Agent 任务",
                LocalDateTime.now().plusMinutes(10));
        agentTask.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        agentTask.setNextExecuteTime(agentTask.getExecuteTime());
        repository.save(agentTask);

        ScheduledTask reminderTask = new ScheduledTask(TEST_USER, "普通提醒",
                LocalDateTime.now().plusMinutes(10));
        reminderTask.setTaskType(ScheduledTask.TASK_TYPE_REMINDER);
        reminderTask.setNextExecuteTime(reminderTask.getExecuteTime());
        repository.save(reminderTask);

        // 查询 Agent 类型任务
        List<ScheduledTask> agentTasks = repository.findByUserIdAndType(TEST_USER, ScheduledTask.TASK_TYPE_AGENT);
        assertEquals(1, agentTasks.size(), "应只查到 Agent 任务");
        assertEquals(ScheduledTask.TASK_TYPE_AGENT, agentTasks.get(0).getTaskType());

        // 查询特定类型的特定状态
        List<ScheduledTask> activeAgentTasks = repository.findByUserIdAndTypeAndStatus(
                TEST_USER, ScheduledTask.TASK_TYPE_AGENT, ScheduledTask.STATUS_ACTIVE);
        assertEquals(1, activeAgentTasks.size());
    }

    @Test
    void shouldFindPausedAgentTaskByTypeAndStatus() {
        ScheduledTask task = new ScheduledTask(TEST_USER, "暂停的 Agent",
                LocalDateTime.now().plusHours(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);
        task.setNextExecuteTime(task.getExecuteTime());
        repository.save(task);

        // 暂停
        repository.markPaused(task.getId(), TEST_USER);

        // 按类型+暂停状态查询
        List<ScheduledTask> pausedAgents = repository.findByUserIdAndTypeAndStatus(
                TEST_USER, ScheduledTask.TASK_TYPE_AGENT, ScheduledTask.STATUS_PAUSED);
        assertEquals(1, pausedAgents.size(), "应查到 1 个已暂停的 Agent 任务");
        assertEquals(ScheduledTask.STATUS_PAUSED, pausedAgents.get(0).getStatus());
    }
}