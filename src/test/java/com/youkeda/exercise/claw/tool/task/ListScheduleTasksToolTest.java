package com.youkeda.exercise.claw.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.feature.task.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * list_schedule_tasks 工具回归测试
 *
 * <p>覆盖：周期任务输出必须包含 {@code next_execute_time}（下次触发时间）。
 * 修复背景：工具此前只返回 {@code execute_time}（配置基准时间，对周期任务可能已过期），
 * LLM 拿它硬猜「下次提醒」导致幻觉（如 22:28 时答「今晚 20:30」）。
 */
class ListScheduleTasksToolTest {

    @TempDir
    File tempDir;

    private ObjectMapper objectMapper;
    private ScheduledTaskRepository repository;
    private ListScheduleTasksTool tool;
    private final String userId = "testUser";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        repository = new ScheduledTaskRepository();
        setField(repository, "dbPath", new File(tempDir, "test-tasks.db").getAbsolutePath());
        repository.init();

        var registry = new ToolRegistry();
        tool = new ListScheduleTasksTool(objectMapper, registry, repository);
        tool.init();
    }

    @Test
    @DisplayName("周期任务应返回 next_execute_time（下次触发时间）")
    void recurringTaskReturnsNextExecuteTime() throws Exception {
        // 周期任务：基准时间今天 20:30（对查询时刻可能已过期），下次触发在明天 20:30
        LocalDateTime anchor = LocalDateTime.of(2026, 7, 31, 20, 30, 0);
        LocalDateTime next = LocalDateTime.of(2026, 8, 1, 20, 30, 0);
        ScheduledTask task = new ScheduledTask(userId, "喝水提醒", anchor);
        task.setRepeatType(ScheduledTask.REPEAT_TYPE_DAILY);
        task.setNextExecuteTime(next);
        repository.save(task);

        String result = tool.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);

        assertEquals("ok", json.get("status").asText());
        JsonNode taskNode = json.get("tasks").get(0);
        assertEquals("2026-07-31 20:30:00", taskNode.get("execute_time").asText());
        // 关键断言：必须带 next_execute_time，且是未来的下次触发时间
        assertTrue(taskNode.has("next_execute_time"),
                "list_schedule_tasks 必须返回 next_execute_time，否则 LLM 会用过期的 execute_time 猜测「下次提醒」");
        assertEquals("2026-08-01 20:30:00", taskNode.get("next_execute_time").asText());
    }

    @Test
    @DisplayName("一次性任务也应返回 next_execute_time（= 执行时间）")
    void onceTaskReturnsNextExecuteTime() throws Exception {
        LocalDateTime exec = LocalDateTime.of(2026, 8, 1, 9, 0, 0);
        ScheduledTask task = new ScheduledTask(userId, "开会", exec);
        repository.save(task);

        String result = tool.execute("{}", context(userId));
        JsonNode json = objectMapper.readTree(result);

        JsonNode taskNode = json.get("tasks").get(0);
        assertEquals("2026-08-01 09:00:00", taskNode.get("execute_time").asText());
        assertTrue(taskNode.has("next_execute_time"));
        assertEquals("2026-08-01 09:00:00", taskNode.get("next_execute_time").asText());
    }

    private ToolExecutionContext context(String userId) {
        return new ToolExecutionContext("", null, userId);
    }

    private void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
