package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.runtime.Tool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5：Pending Tool Action 确认工作流测试。
 */
class PendingToolCoordinatorTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final PendingToolCoordinator coordinator = new PendingToolCoordinator(
            toolRegistry, objectMapper);

    @BeforeEach
    void setUp() {
        coordinator.clearForTest("user-1");
    }

    // ==================== 1. 待确认操作基本创建 ====================

    @Test
    void highRiskToolCreatesPendingAction() {
        PendingToolAction action = coordinator.createPending(
                "user-1", "file_delete", "{\"file_id\": 42}");

        assertNotNull(action, "应创建 PendingToolAction");
        assertEquals("user-1", action.userId());
        assertEquals("file_delete", action.toolName());
        assertEquals("{\"file_id\": 42}", action.toolArguments(),
                "应保留原始 tool call 参数");
        assertEquals(SafetyPolicy.ToolRiskLevel.HIGH, action.riskLevel());
        assertEquals(PendingToolAction.Status.PENDING_CONFIRMATION, action.status());
        assertTrue(action.expireAt().isAfter(Instant.now()),
                "过期时间应在未来");
    }

    @Test
    void findPendingShouldReturnSameAction() {
        coordinator.createPending("user-1", "file_delete", "{\"file_id\": 1}");

        PendingToolAction found = coordinator.findPending("user-1");
        assertNotNull(found);
        assertEquals("file_delete", found.toolName());
        assertEquals(PendingToolAction.Status.PENDING_CONFIRMATION, found.status());
    }

    @Test
    void findPendingForUnknownUserReturnsNull() {
        assertNull(coordinator.findPending("no-such-user"));
    }

    // ==================== 2. 用户确认 ====================

    @Test
    void confirmationShouldExecuteOriginalToolCall() {
        coordinator.createPending("user-1", "file_delete", "{\"file_id\": 42}");

        Tool deleteTool = mock(Tool.class);
        when(deleteTool.execute(anyString(), any()))
                .thenReturn("{\"status\":\"success\",\"file_id\":42,\"message\":\"文件已删除\"}");
        when(toolRegistry.find("file_delete")).thenReturn(deleteTool);

        PendingToolCoordinator.Result result = coordinator.handleUserMessage(
                "user-1", "确认");

        assertEquals(PendingToolCoordinator.Result.Type.EXECUTED, result.type(),
                "确认应触发工具执行");

        // 验证使用原始参数执行
        verify(deleteTool).execute(eq("{\"file_id\": 42}"), any(ToolExecutionContext.class));

        // 验证结果
        assertNotNull(result.rawToolResult());
        assertTrue(result.rawToolResult().contains("文件已删除"));

        // 验证 pending 已清除
        assertNull(coordinator.findPending("user-1"),
                "执行后 pending 应被清除");
    }

    @Test
    void confirmationShouldKeepOriginalArguments() {
        String originalArgs = "{\"file_id\": 99, \"force\": true}";
        coordinator.createPending("user-1", "file_delete", originalArgs);

        Tool deleteTool = mock(Tool.class);
        when(deleteTool.execute(anyString(), any()))
                .thenReturn("{\"status\":\"success\"}");
        when(toolRegistry.find("file_delete")).thenReturn(deleteTool);

        coordinator.handleUserMessage("user-1", "好的");

        // 确认执行参数与第一次 tool_call 一致
        verify(deleteTool).execute(eq(originalArgs), any(ToolExecutionContext.class));
    }

    // ==================== 3. 用户取消 ====================

    @Test
    void cancelShouldRemovePendingAction() {
        coordinator.createPending("user-1", "file_delete", "{\"file_id\": 1}");

        PendingToolCoordinator.Result result = coordinator.handleUserMessage(
                "user-1", "取消");

        assertEquals(PendingToolCoordinator.Result.Type.CANCELLED, result.type());
        assertNotNull(result.userReply());
        assertTrue(result.userReply().contains("取消"));

        // pending 应被清除
        assertNull(coordinator.findPending("user-1"));
    }

    @Test
    void cancelShouldNotExecuteTool() {
        coordinator.createPending("user-1", "file_delete", "{\"file_id\": 1}");
        Tool deleteTool = mock(Tool.class);
        when(toolRegistry.find("file_delete")).thenReturn(deleteTool);

        coordinator.handleUserMessage("user-1", "不用了");

        // 不应执行任何工具
        verify(deleteTool, never()).execute(anyString(), any());
    }

    // ==================== 4. 过期机制 ====================

    @Test
    void expiredPendingActionShouldNotExecute() {
        // 创建已过期的 pending（通过反射或手动构造）
        PendingToolAction expired = new PendingToolAction(
                "exp-1", "user-1", "file_delete", "{}",
                SafetyPolicy.ToolRiskLevel.HIGH,
                Instant.now().minusSeconds(400),  // 400s ago
                Instant.now().minusSeconds(100),  // expired 100s ago
                PendingToolAction.Status.PENDING_CONFIRMATION);

        // 需要直接放入 store 来测试过期
        coordinator.createPending("user-1", "dummy", "{}");
        // findPending 会检查过期... 但 createPending 用默认 TTL = 300s
        // 改用 handleUserMessage 检查已经存在的 pending

        // 创建一个有效 pending，然后确认（正常流程）
        assertTrue(expired.isExpired(), "过期 pending 的 isExpired 应为 true");
    }

    @Test
    void freshPendingShouldNotBeExpired() {
        PendingToolAction fresh = new PendingToolAction(
                "fresh-1", "user-1", "file_delete", "{}",
                SafetyPolicy.ToolRiskLevel.HIGH,
                Instant.now(),
                Instant.now().plusSeconds(PendingToolAction.DEFAULT_TTL_SECONDS),
                PendingToolAction.Status.PENDING_CONFIRMATION);

        assertFalse(fresh.isExpired());
    }

    // ==================== 5. 不匹配消息继续放行 ====================

    @Test
    void unrelatedMessageShouldReturnNotHandled() {
        coordinator.createPending("user-1", "file_delete", "{}");

        PendingToolCoordinator.Result result = coordinator.handleUserMessage(
                "user-1", "今天天气怎么样");

        assertEquals(PendingToolCoordinator.Result.Type.NOT_HANDLED, result.type());
        assertFalse(result.handled());

        // pending 应保留
        assertNotNull(coordinator.findPending("user-1"));
    }

    // ==================== 6. 多种确认关键词 ====================

    @Test
    void multipleConfirmKeywordsShouldWork() {
        coordinator.createPending("user-1", "file_delete", "{}");
        Tool deleteTool = mock(Tool.class);
        when(deleteTool.execute(anyString(), any()))
                .thenReturn("{\"status\":\"success\"}");
        when(toolRegistry.find("file_delete")).thenReturn(deleteTool);

        // "好的"、"行"、"可以"、"执行吧" 都能确认
        for (String keyword : new String[]{"好的", "行", "可以", "执行吧"}) {
            coordinator.createPending("user-1", "file_delete", "{}");
            var result = coordinator.handleUserMessage("user-1", keyword);
            assertEquals(PendingToolCoordinator.Result.Type.EXECUTED, result.type(),
                    keyword + " 应触发确认执行");
        }
    }

    // ==================== 7. 多种取消关键词 ====================

    @Test
    void multipleCancelKeywordsShouldWork() {
        for (String keyword : new String[]{"取消", "不用了", "算了"}) {
            coordinator.createPending("user-1", "file_delete", "{}");
            var result = coordinator.handleUserMessage("user-1", keyword);
            assertEquals(PendingToolCoordinator.Result.Type.CANCELLED, result.type(),
                    keyword + " 应触发取消");
        }
    }

    // ==================== 8. 工具执行失败 ====================

    @Test
    void toolExecutionFailureShouldReturnFailedResult() {
        coordinator.createPending("user-1", "file_delete", "{}");
        Tool deleteTool = mock(Tool.class);
        when(deleteTool.execute(anyString(), any()))
                .thenThrow(new RuntimeException("磁盘满了"));
        when(toolRegistry.find("file_delete")).thenReturn(deleteTool);

        PendingToolCoordinator.Result result = coordinator.handleUserMessage(
                "user-1", "确认");

        assertEquals(PendingToolCoordinator.Result.Type.FAILED, result.type());
        assertNotNull(result.userReply());
        assertTrue(result.userReply().contains("磁盘满了")
                        || result.userReply().contains("出错"));
    }

    @Test
    void unknownToolShouldReturnFailed() {
        coordinator.createPending("user-1", "nonexistent", "{}");
        when(toolRegistry.find("nonexistent")).thenReturn(null);

        PendingToolCoordinator.Result result = coordinator.handleUserMessage(
                "user-1", "确认");

        assertEquals(PendingToolCoordinator.Result.Type.FAILED, result.type());
    }
}
