package com.youkeda.exercise.claw.feature.task.executor;

import com.youkeda.exercise.claw.agent.ReActAgentExecutor;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.notification.NotificationSink;
import com.youkeda.exercise.claw.agent.model.MessageKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AgentTaskExecutor 单元测试
 *
 * <p>覆盖：
 * - Agent 任务正常执行流程
 * - Agent 任务执行结果通过微信发送
 * - 异常场景
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskExecutorTest {

    @Mock
    private ReActAgentExecutor agentExecutor;

    @Mock
    private NotificationSink notificationSink;

    private AgentTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgentTaskExecutor(agentExecutor, notificationSink);
    }

    @Test
    void shouldExecuteAgentTaskAndSendResult() throws Exception {
        // 准备
        ScheduledTask task = new ScheduledTask("test_user", "总结今天AI新闻",
                java.time.LocalDateTime.now().plusMinutes(30));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);

        when(agentExecutor.execute(any())).thenReturn("这是今天的AI新闻总结：...");

        // 执行
        executor.execute(task);

        // 验证：Agent 被调用
        ArgumentCaptor<com.youkeda.exercise.claw.agent.AgentContext> contextCaptor =
                ArgumentCaptor.forClass(com.youkeda.exercise.claw.agent.AgentContext.class);
        verify(agentExecutor, times(1)).execute(contextCaptor.capture());

        com.youkeda.exercise.claw.agent.AgentContext ctx = contextCaptor.getValue();
        assertEquals("test_user", ctx.getUserId());
        assertEquals("总结今天AI新闻", ctx.getMessage());
        assertEquals(MessageKind.TEXT, ctx.getMessageType());

        // 验证：结果发送到微信
        verify(notificationSink).publish(eq("test_user"), eq("AGENT_TASK"), anyString(),
                anyString(), anyInt(), isNull());
    }

    @Test
    void shouldHandleAgentExecutionError() {
        // 准备
        ScheduledTask task = new ScheduledTask("error_user", "查询天气",
                java.time.LocalDateTime.now().plusMinutes(5));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);

        // 模拟 Agent 执行失败
        when(agentExecutor.execute(any())).thenThrow(new RuntimeException("LLM 调用超时"));

        // 执行 & 验证：异常应传播给调用方
        assertThrows(RuntimeException.class, () -> executor.execute(task));

        // WeChat 不应发送消息
        verify(notificationSink, never()).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldIncludeTaskContentInContext() throws Exception {
        // 准备
        String agentContent = "帮我写一份项目周报";
        ScheduledTask task = new ScheduledTask("user_w", agentContent,
                java.time.LocalDateTime.now().plusHours(1));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);

        when(agentExecutor.execute(any())).thenReturn("周报已生成：...");

        // 执行
        executor.execute(task);

        // 验证：任务的 content 作为用户消息传入 AgentContext
        ArgumentCaptor<com.youkeda.exercise.claw.agent.AgentContext> captor =
                ArgumentCaptor.forClass(com.youkeda.exercise.claw.agent.AgentContext.class);
        verify(agentExecutor).execute(captor.capture());
        assertEquals(agentContent, captor.getValue().getMessage());
    }

    @Test
    void shouldNotCallWechatWhenAgentFails() {
        // 准备
        ScheduledTask task = new ScheduledTask("user_fail", "提醒任务",
                java.time.LocalDateTime.now().plusMinutes(10));
        task.setTaskType(ScheduledTask.TASK_TYPE_AGENT);

        when(agentExecutor.execute(any())).thenThrow(new RuntimeException("Agent 执行异常"));

        // 执行
        assertThrows(RuntimeException.class, () -> executor.execute(task));

        // WeChat 不应发送
        verify(notificationSink, never()).publish(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    }
}
