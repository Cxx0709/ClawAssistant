package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionLoopSkillReplyGuardTest {

    @Test
    void usesStructuredQuestionsWhenLlmReturnsEmptyAfterToolResult() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.chatWithTools(any(), any(), any()))
                .thenReturn(new LLMResponse(null, List.of(), "stop"));

        ExecutionLoop loop = new ExecutionLoop(
                llm, mock(ToolExecutor.class), mock(PlanStore.class),
                mock(PlanValidator.class), new ObjectMapper(), List.of(),
                new SkillReplyGuardRegistry(List.of()));
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", "帮我规划杭州4日游"));
        messages.add(new Message("tool", """
                {"status":"NEED_MORE_INFORMATION","questions":[
                  "从哪里出发或在哪里集合？",
                  "预计多少人参加？",
                  "计划什么时候出发？"
                ]}
                """, null, null, null, "tc-1", null));

        ExecutionLoop.Result result = loop.run(
                "sys", messages, List.of(), null,
                mock(ToolExecutionContext.class), SkillSession.create("u"),
                "req", "travel", "帮我规划杭州4日游");

        assertEquals(ExecutionLoop.LoopStatus.TEXT_REPLY, result.status());
        assertTrue(result.reply().contains("从哪里出发"));
        assertTrue(result.reply().contains("多少人参加"));
        assertFalse(result.reply().contains("什么时候出发"), "降级追问一次最多展示两项");
        verify(llm, times(1)).chatWithTools(any(), any(), any());
    }

    @Test
    void retriesOneEmptyTextResponseWithoutTools() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.chatWithTools(any(), any(), any()))
                .thenReturn(new LLMResponse(" ", List.of(), "stop"))
                .thenReturn(new LLMResponse("请告诉我出发城市。", List.of(), "stop"));

        ExecutionLoop loop = new ExecutionLoop(
                llm, mock(ToolExecutor.class), mock(PlanStore.class),
                mock(PlanValidator.class), new ObjectMapper(), List.of(),
                new SkillReplyGuardRegistry(List.of()));
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", "帮我规划杭州4日游"));

        ExecutionLoop.Result result = loop.run(
                "sys", messages, List.of(mock(com.youkeda.exercise.claw.ai.llm.ToolDefinition.class)),
                null, mock(ToolExecutionContext.class), SkillSession.create("u"),
                "req", "travel", "帮我规划杭州4日游");

        assertEquals("请告诉我出发城市。", result.reply());
        verify(llm, times(2)).chatWithTools(any(), any(), any());
        assertTrue(messages.stream().anyMatch(message ->
                message.role() == MessageRole.SYSTEM
                        && message.content().contains("响应正文为空")));
    }

    @Test
    void textReplyBlockedByGuardInjectsCorrectionAndRetries() {
        LLMClient llm = mock(LLMClient.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        PlanValidator validator = mock(PlanValidator.class);
        ObjectMapper om = new ObjectMapper();

        // 第一轮返回文本（会被 guard 拦），第二轮返回放行文本
        when(llm.chatWithTools(any(), any(), any()))
                .thenReturn(new LLMResponse("这是被拦的回复", List.of(), "stop"))
                .thenReturn(new LLMResponse("这是放行的回复", List.of(), "stop"));

        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(List.of(
                new SkillReplyGuard() {
                    @Override public String getSkillName() { return "travel"; }
                    @Override public GuardResult validate(GuardContext ctx) {
                        return ctx.reply().contains("被拦")
                                ? GuardResult.reject("先调 travel_collect")
                                : GuardResult.allow();
                    }
                }));

        ExecutionLoop loop = new ExecutionLoop(
                llm, toolExecutor, mock(PlanStore.class), validator, om,
                List.of(), registry);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", "我要去三亚"));
        ExecutionLoop.Result result = loop.run(
                "sys", messages, List.of(), null,
                mock(ToolExecutionContext.class), SkillSession.create("u"),
                "req", "travel", "我要去三亚");

        // 守卫拦截时注入的 correction 必须已作为 system 消息进入消息列表，
        // 否则仅验证「LLM 调用 2 次 + 最终放行」无法覆盖 messages.add 被移除的回归
        assertTrue(messages.stream().anyMatch(m ->
                        m.role() == MessageRole.SYSTEM && m.content() != null
                                && m.content().contains("先调 travel_collect")),
                "correction 提示必须已作为 system 消息注入到消息列表中");
        assertEquals(ExecutionLoop.LoopStatus.TEXT_REPLY, result.status());
        assertEquals("这是放行的回复", result.reply());
        verify(llm, times(2)).chatWithTools(any(), any(), any());
    }

    @Test
    void guardRejectWithNullCorrectionThrowsNpe() {
        LLMClient llm = mock(LLMClient.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        PlanValidator validator = mock(PlanValidator.class);
        ObjectMapper om = new ObjectMapper();

        // 守卫拒绝但 correction 为 null——防御必须在此显式失败，而不是把 null 注入消息
        when(llm.chatWithTools(any(), any(), any()))
                .thenReturn(new LLMResponse("这是被拦的回复", List.of(), "stop"));

        SkillReplyGuardRegistry registry = new SkillReplyGuardRegistry(List.of(
                new SkillReplyGuard() {
                    @Override public String getSkillName() { return "travel"; }
                    @Override public GuardResult validate(GuardContext ctx) {
                        return GuardResult.reject(null);
                    }
                }));

        ExecutionLoop loop = new ExecutionLoop(
                llm, toolExecutor, mock(PlanStore.class), validator, om,
                List.of(), registry);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", "我要去三亚"));
        assertThrows(NullPointerException.class, () -> loop.run(
                "sys", messages, List.of(), null,
                mock(ToolExecutionContext.class), SkillSession.create("u"),
                "req", "travel", "我要去三亚"));
    }
}
