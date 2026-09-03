package com.youkeda.exercise.claw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionLoopBlankStreamingRetryTest {

    @Test
    void streamingBlankWithReasoningShouldFallBackToNonStreaming() {
        LLMClient llm = mock(LLMClient.class);
        AtomicInteger streamCalls = new AtomicInteger();

        when(llm.chatWithToolsStreaming(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    if (streamCalls.getAndIncrement() == 0) {
                        return new LLMResponse(null, List.of(), "stop", "仅思考内容");
                    }
                    return new LLMResponse("请告诉我出发城市。", List.of(), "stop");
                });

        ExecutionLoop loop = new ExecutionLoop(
                llm, mock(ToolExecutor.class), mock(PlanStore.class),
                mock(PlanValidator.class), new ObjectMapper(), List.of(),
                new SkillReplyGuardRegistry(List.of()));

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", "帮我规划杭州4日游"));

        ObjectMapper mapper = new ObjectMapper();
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("travel_collect", "collect trip information", mapper.createObjectNode()));

        ExecutionLoop.Result result = loop.run(
                "sys", messages, tools, null,
                mock(ToolExecutionContext.class), SkillSession.create("u"),
                "req", "travel", "帮我规划杭州4日游",
                chunk -> {});

        assertEquals(ExecutionLoop.LoopStatus.TEXT_REPLY, result.status());
        assertEquals("请告诉我出发城市。", result.reply());
        verify(llm, times(2)).chatWithToolsStreaming(any(), any(), any(), any());
        verifyNoMoreInteractions(llm);
    }
}
