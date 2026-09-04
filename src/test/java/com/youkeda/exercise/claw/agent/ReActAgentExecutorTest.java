package com.youkeda.exercise.claw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.PlanTask;
import com.youkeda.exercise.claw.agent.plan.DefaultPlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.ai.retrieval.*;
import com.youkeda.exercise.claw.agent.runtime.Tool;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutor;
import com.youkeda.exercise.claw.agent.runtime.ExecutionLoop;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuardRegistry;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;
import com.youkeda.exercise.claw.agent.skill.*;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReActAgentExecutorTest {

    @Test
    void shouldStopOfferingToolsWhenWholeBatchWasBlocked() {
        Fixture fixture = fixture();
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new LLMResponse(null,
                                List.of(new LLMResponse.ToolCall("tc1", "unknown_tool", "{}")),
                                "tool_calls"),
                        new LLMResponse("请补充必要信息。", List.of(), "stop"));

        String reply = fixture.executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("帮我做方案"));

        assertEquals("请补充必要信息。", reply);
        ArgumentCaptor<List<ToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(fixture.llmClient, times(2)).chatWithTools(anyString(), anyList(), tools.capture());
        assertFalse(tools.getAllValues().get(0).isEmpty());
        assertTrue(tools.getAllValues().get(1).isEmpty());
    }

    @Test
    void shouldSynthesizeExistingResultsInsteadOfReturningTimeoutAtRoundLimit() {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call <= 12) {
                return new LLMResponse(null,
                        List.of(new LLMResponse.ToolCall(
                                "tc" + call, "dummy_tool", "{\"round\":" + call + "}")),
                        "tool_calls");
            }
            return new LLMResponse("已根据现有结果整理回复。", List.of(), "stop");
        });

        String reply = fixture.executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("生成完整方案"));

        assertEquals("已根据现有结果整理回复。", reply);
        assertEquals(13, calls.get());
    }

    @Test
    void shouldNotReportTimeoutWhenFinalSynthesisStillRequestsTool() {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            return new LLMResponse(null,
                    List.of(new LLMResponse.ToolCall(
                            "tc" + call,
                            call <= 12 ? "dummy_tool" : "budget_calculator",
                            "{\"round\":" + call + "}")),
                    "tool_calls");
        });

        String reply = fixture.executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("生成完整方案"));

        assertTrue(reply.contains("当前可用信息") || reply.contains("已有结果"));
        assertFalse(reply.contains("处理请求超时"));
        assertFalse(reply.contains("继续生成"));
        assertEquals(16, calls.get());
    }

    @Test
    void shouldRemoveLegacyLimitReplyWhenUserContinuesGeneration() {
        Fixture fixture = fixture();
        when(fixture.contextStore.getHistory(anyInt())).thenReturn(List.of(
                new com.youkeda.exercise.claw.agent.memory.Message("user", "生成团建方案"),
                new com.youkeda.exercise.claw.agent.memory.Message("assistant",
                        "本轮处理步骤已达到上限，请回复" + "“" + "继续生成" + "”" + "。"),
                new com.youkeda.exercise.claw.agent.memory.Message("user", "继续生成")));
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new LLMResponse("继续完成方案。", List.of(), "stop"));

        fixture.executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("继续生成"));

        ArgumentCaptor<List<com.youkeda.exercise.claw.agent.memory.Message>> messages =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.llmClient).chatWithTools(anyString(), messages.capture(), anyList());
        assertFalse(messages.getValue().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("本轮处理步骤已达到上限")));
    }

    @Test
    void shouldHandleSimpleChatQuickPath() {
        Fixture fixture = fixture();
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new LLMResponse("你好！有什么可以帮你的？", List.of(), "stop"));

        AgentContext context = new AgentContext()
                .setUserId("test-user")
                .setMessage("你好");
        String reply = fixture.executor.execute(context);

        assertEquals("你好！有什么可以帮你的？", reply);
    }

    @Test
    void shouldNotEnterToolLoopAfterPermanentQuickChatFailure() {
        Fixture fixture = fixture();
        when(fixture.llmClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(null);
        when(fixture.llmClient.hasPermanentFailure()).thenReturn(true);
        when(fixture.llmClient.getLastFailureSummary()).thenReturn("LLM HTTP 401");

        String reply = fixture.executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("我今天心情有点烦，能聊聊吗"));

        assertEquals("抱歉，AI 服务暂时不可用，请稍后再试。", reply);
        verify(fixture.llmClient, times(1))
                .chatWithTools(anyString(), anyList(), anyList());
    }

    private Fixture fixture() {
        ObjectMapper objectMapper = new ObjectMapper();
        LLMClient llmClient = mock(LLMClient.class);
        ContextStore contextStore = mock(ContextStore.class);
        when(contextStore.getHistory(anyInt())).thenReturn(List.of());

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "dummy_tool";
            }

            @Override
            public String getDescription() {
                return "测试工具";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode getParameters() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public String execute(String argumentsJson, com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext context) {
                return "{\"status\":\"SUCCESS\"}";
            }
        });

        PlanStore planStore = new DefaultPlanStore();
        PlanValidator planValidator = new PlanValidator();
        SafetyPolicy safetyPolicy = new SafetyPolicy();
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        when(longTermMemoryService.recall(anyString())).thenReturn(List.of());

        // Skill dependencies (mocked to fallback to common mode)
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.route(anyString(), anyString()))
                .thenReturn(SkillRoutingResult.fallback());
        SkillSessionStore skillSessionStore = mock(SkillSessionStore.class);
        when(skillSessionStore.find(anyString())).thenReturn(java.util.Optional.empty());
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        SkillsProperties skillsProperties = new SkillsProperties();
        skillsProperties.setGlobalTools(new java.util.LinkedHashSet<>(Set.of("dummy_tool")));
        UserExecutionContext userExecutionContext = new UserExecutionContext();
        when(llmClient.getSystemPrompt()).thenReturn("你是 Claw助手，一个智能AI助手。");
        SkillExecutionDispatcher skillExecutionDispatcher = mock(SkillExecutionDispatcher.class);
        when(skillExecutionDispatcher.dispatch(
                nullable(SkillDefinition.class), anyString(), any(SkillSession.class)))
                .thenAnswer(invocation -> SkillExecutionResult.notHandled(
                        invocation.getArgument(2)));

        ToolExecutor toolExecutor = new ToolExecutor(
                registry, safetyPolicy, mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), mock(ToolResultStatusParser.class),
                planStore, objectMapper, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));
        ExecutionLoop executionLoop = new ExecutionLoop(
                llmClient, toolExecutor, planStore, planValidator, objectMapper,
                java.util.List.of(),
                new SkillReplyGuardRegistry(java.util.List.of()));

        ReActAgentExecutor executor = new ReActAgentExecutor(
                llmClient, registry, contextStore, objectMapper,
                planStore, longTermMemoryService,
                mock(com.youkeda.exercise.claw.agent.memory.ConversationSummaryService.class),
                skillRouter, skillSessionStore, skillRegistry, skillsProperties, userExecutionContext,
                mock(SkillKnowledgeService.class),
                mock(AgentActivityRecorder.class),
                skillExecutionDispatcher,
                executionLoop,
                new CommonCapabilityRegistry(skillsProperties),
                notHandlingPendingCoordinator());
        return new Fixture(llmClient, executor, contextStore);
    }

    private static PendingToolCoordinator notHandlingPendingCoordinator() {
        PendingToolCoordinator mock = mock(PendingToolCoordinator.class);
        when(mock.handleUserMessage(anyString(), anyString()))
                .thenReturn(PendingToolCoordinator.Result.notHandled());
        return mock;
    }

    private record Fixture(LLMClient llmClient, ReActAgentExecutor executor,
                           ContextStore contextStore) {
    }
}
