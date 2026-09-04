package com.youkeda.exercise.claw.feature.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CourseQueryActions 查询行为测试
 *
 * <p>重点验证「课表为空 → 进入课表导入状态（WAITING_FILE）」的状态转移，
 * 保证用户查看空课表后发送的文件能被 MessageRouter 路由到 CourseImportHandler。
 */
class CourseQueryActionsTest {

    private CourseQueryActions buildActions(CourseService courseService,
                                            CourseImportStateManager stateManager) {
        return new CourseQueryActions(
                courseService,
                mock(CourseRepository.class),
                new SemesterConfig(),
                mock(SemesterService.class),
                mock(CourseMessageFormatter.class),
                new ObjectMapper(),
                stateManager,
                mock(ScheduleReminderService.class));
    }

    @Test
    @DisplayName("空课表查询后进入等待文件状态（WAITING_FILE）")
    void emptyScheduleEntersWaitingFile() {
        CourseService courseService = mock(CourseService.class);
        when(courseService.getAllCourses("u1")).thenReturn(List.of());
        CourseImportStateManager stateManager = new CourseImportStateManager();

        CourseQueryActions actions = buildActions(courseService, stateManager);
        String result = actions.handleQueryAll("u1");

        assertTrue(result.contains("query_all"));
        assertEquals(CourseImportStateManager.Phase.WAITING_FILE, stateManager.getPhase("u1"));
    }

    @Test
    @DisplayName("非空课表查询不改变状态（保持 NONE）")
    void nonEmptyScheduleKeepsNone() {
        CourseService courseService = mock(CourseService.class);
        when(courseService.getAllCourses("u1")).thenReturn(List.of(
                new CourseEntity("u1", "高数", "张老师", 1, 1, 2, "A101", 1, 16, CourseEntity.WEEK_ALL)
        ));
        CourseImportStateManager stateManager = new CourseImportStateManager();

        CourseQueryActions actions = buildActions(courseService, stateManager);
        actions.handleQueryAll("u1");

        assertEquals(CourseImportStateManager.Phase.NONE, stateManager.getPhase("u1"));
    }
}
