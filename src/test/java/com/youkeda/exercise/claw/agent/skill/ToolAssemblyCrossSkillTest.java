package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.ReActAgentExecutor;
import com.youkeda.exercise.claw.agent.SafetyPolicy;
import com.youkeda.exercise.claw.agent.SkillSessionUpdater;
import com.youkeda.exercise.claw.agent.ToolResultStatusParser;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.plan.DefaultPlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanStore;
import com.youkeda.exercise.claw.agent.plan.PlanValidator;
import com.youkeda.exercise.claw.agent.runtime.*;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import com.youkeda.exercise.claw.skill.*;
import com.youkeda.exercise.claw.agent.AgentContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨 Skill 集成测试：验证三级工具白名单在不同 Skill 场景下的正确性。
 *
 * <p>核心验证点：
 * <ul>
 *   <li>common capability tools 对所有 Skill 可见</li>
 *   <li>各 Skill 专属工具不泄露到其他 Skill</li>
 *   <li>effectiveTools 三层正确去重合并</li>
 * </ul>
 */
class ToolAssemblyCrossSkillTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Case 1: Travel + file_generate ====================

    @Test
    void travelSkillShouldIncludeFileGenerateAlongsideTravelTools() {
        List<String> toolNames = captureEffectiveToolNames(
                buildSkillsProperties(),
                "travel",
                SkillRoutingResult.fallback());

        // Travel-specific tools MUST be present
        assertTrue(toolNames.contains("travel_collect"), "travel 技能应包含 travel_collect");
        assertTrue(toolNames.contains("travel_revise"), "travel 技能应包含 travel_revise");

        // Common capability tools MUST be present (file_generate is cross-skill)
        assertTrue(toolNames.contains("file_generate"),
                "file_generate 是通用能力工具，travel skill 下必须可见");
        assertTrue(toolNames.contains("web_search"),
                "web_search 是通用能力工具，travel skill 下必须可见");

        // Global tools MUST be present
        assertTrue(toolNames.contains("memory_manage"),
                "memory_manage 是系统工具，所有 skill 下必须可见");
    }

    // ==================== Case 2: Campus + create_schedule_task ====================

    @Test
    void campusSkillShouldIncludeScheduleTaskAlongsideCourseTools() {
        List<String> toolNames = captureEffectiveToolNames(
                buildSkillsProperties(),
                "campus",
                SkillRoutingResult.fallback());

        // Campus-specific tools MUST be present
        assertTrue(toolNames.contains("course_schedule"), "campus 技能应包含 course_schedule");
        assertTrue(toolNames.contains("exam_schedule"), "campus 技能应包含 exam_schedule");

        // Common capability tools MUST be present
        assertTrue(toolNames.contains("create_schedule_task"),
                "create_schedule_task 是通用能力工具，campus skill 下必须可见");
        assertTrue(toolNames.contains("list_schedule_tasks"),
                "list_schedule_tasks 是通用能力工具，campus skill 下必须可见");
    }

    // ==================== Case 3: Image skill — image_generate directly ====================

    @Test
    void imageSkillShouldHaveImageGenerateAvailable() {
        List<String> toolNames = captureEffectiveToolNames(
                buildSkillsProperties(),
                "image",
                SkillRoutingResult.fallback());

        // image_generate is in BOTH common capability AND image.requiredTools — must appear once
        long count = toolNames.stream().filter("image_generate"::equals).count();
        assertEquals(1, count,
                "image_generate 同时在 common-capability 和 image.requiredTools 中，应去重为 1 个");

        assertTrue(toolNames.contains("image_generate"),
                "image_generate 必须可见（既是通用能力又是 image 专属工具）");
    }

    // ==================== Case 4: No irrelevant domain tools leaked ====================

    @Test
    void weatherSkillShouldNotExposeCourseOrAnimeTools() {
        List<String> toolNames = captureEffectiveToolNames(
                buildSkillsProperties(),
                "weather",
                SkillRoutingResult.fallback());

        // Weather-specific
        assertTrue(toolNames.contains("weather_query"), "weather 技能应包含 weather_query");

        // Common capabilities
        assertTrue(toolNames.contains("web_search"), "web_search 应对 weather 可见");

        // Domain tools from OTHER skills MUST NOT leak
        assertFalse(toolNames.contains("course_schedule"),
                "course_schedule 是 campus 专属工具，不应泄露到 weather");
        assertFalse(toolNames.contains("exam_schedule"),
                "exam_schedule 是 campus 专属工具，不应泄露到 weather");
        assertFalse(toolNames.contains("anime_recommend"),
                "anime_recommend 是 anime 专属工具，不应泄露到 weather");
        assertFalse(toolNames.contains("anime_subscribe"),
                "anime_subscribe 是 anime 专属工具，不应泄露到 weather");
        assertFalse(toolNames.contains("travel_collect"),
                "travel_collect 是 travel 专属工具，不应泄露到 weather");
        assertFalse(toolNames.contains("transport_recommend"),
                "transport_recommend 是 transport 专属工具，不应泄露到 weather");
    }

    // ==================== 工具顺序稳定性 ====================

    @Test
    void effectiveToolOrderShouldBeStable() {
        List<String> run1 = captureEffectiveToolNames(
                buildSkillsProperties(), "travel", SkillRoutingResult.fallback());
        List<String> run2 = captureEffectiveToolNames(
                buildSkillsProperties(), "travel", SkillRoutingResult.fallback());
        List<String> run3 = captureEffectiveToolNames(
                buildSkillsProperties(), "travel", SkillRoutingResult.fallback());

        assertEquals(run1, run2, "连续运行 effectiveTools 顺序应一致");
        assertEquals(run1, run3, "连续运行 effectiveTools 顺序应一致");
    }

    // ==================== 空 common-capability 场景 ====================

    @Test
    void emptyCommonCapabilityShouldNotBreakToolAssembly() {
        SkillsProperties props = new SkillsProperties();
        props.setGlobalTools(new LinkedHashSet<>(Set.of("memory_manage")));
        // No common-capability-tools set
        SkillDefinition commonSkill = new SkillDefinition(
                "common", null, 0, Set.of(), Set.of(), Set.of(),
                null, null, null, null, true);

        List<String> toolNames = captureEffectiveToolNamesWithSkill(
                props, commonSkill);

        assertTrue(toolNames.contains("memory_manage"),
                "即使 common-capability-tools 为空，global tools 也应正常");
        // common skill has no optionalTools → only global
        assertEquals(1, toolNames.size(),
                "common skill 在无 common-capability-tools 时应仅有 global tools");
    }

    // ==================== 通用能力 + 无活跃 Skill ====================

    @Test
    void noActiveSkillShouldStillHaveCommonCapabilityTools() {
        SkillsProperties props = buildSkillsProperties();
        List<String> toolNames = captureEffectiveToolNamesWithSkill(props, null);

        // Global + common capability, no skill tools
        assertTrue(toolNames.contains("memory_manage"));
        assertTrue(toolNames.contains("web_search"));
        assertTrue(toolNames.contains("file_generate"));
        assertFalse(toolNames.contains("weather_query"),
                "无活跃 skill 时，skill 专属工具不应暴露");
    }

    // ==================== helpers ====================

    /**
     * 构建与 skills.yml 一致的 SkillsProperties 用于测试。
     * 只注册测试所需的代表性工具，不注册所有 30+ 工具。
     */
    private SkillsProperties buildSkillsProperties() {
        SkillsProperties props = new SkillsProperties();
        props.setGlobalTools(new LinkedHashSet<>(List.of(
                "memory_manage", "time_query", "skill_knowledge_manage")));
        props.setCommonCapabilityTools(new LinkedHashSet<>(List.of(
                "file_generate", "file_save", "file_read", "file_search",
                "image_generate", "text_to_speech",
                "create_schedule_task", "list_schedule_tasks",
                "update_schedule_task", "cancel_schedule_task",
                "web_search", "holiday_check")));

        // Register all skill definitions from skills.yml (trimmed to match)
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        skills.put("common", new SkillDefinition("common", null, 0, Set.of("通用"),
                Set.of(), Set.of(), "prompts/skills/common.txt", null, null, null, true));
        skills.put("campus", new SkillDefinition("campus", null, 4, Set.of("校园", "课程"),
                Set.of(), Set.of("course_schedule", "exam_schedule", "exam_reminder_setup"),
                "prompts/skills/campus.txt", null, null, null, true));
        skills.put("travel", new SkillDefinition("travel", null, 5, Set.of("出行", "方案"),
                Set.of("travel_collect", "travel_save_options", "travel_select_option", "travel_revise"),
                Set.of("travel_calculate_cost", "map_search_place", "map_route_planning",
                        "map_distance_calculate", "weather_query", "transport_recommend",
                        "place_image_search"),
                "prompts/skills/travel.txt", null, null, null, true));
        skills.put("transport", new SkillDefinition("transport", null, 4, Set.of("出行", "交通"),
                Set.of("transport_recommend"),
                Set.of("map_search_place", "map_route_planning",
                        "map_distance_calculate"),
                "prompts/skills/transport.txt", "transportTriggerPolicy", null, null, true));
        skills.put("weather", new SkillDefinition("weather", null, 2, Set.of("天气"),
                Set.of("weather_query"), Set.of(),
                "prompts/skills/weather.txt", "keywordTriggerPolicy", null, null, true));
        skills.put("anime", new SkillDefinition("anime", null, 4, Set.of("动漫", "番剧"),
                Set.of(), Set.of("anime_recommend", "anime_subscribe"),
                "prompts/skills/anime.txt", null, null, null, true));
        skills.put("image", new SkillDefinition("image", null, 5, Set.of("图片"),
                Set.of("image_generate"), Set.of(),
                "prompts/skills/image.txt", null, null, null, true));
        skills.put("research", new SkillDefinition("research", null, 3,
                Set.of("信息", "搜索"), Set.of(), Set.of(),
                "prompts/skills/research.txt", "researchTriggerPolicy",
                null, new SkillExecutionConfig(SkillExecutionMode.BACKGROUND_WORKFLOW,
                        "researchSkillExecutor"), true));

        props.setSkills(skills);
        return props;
    }

    /**
     * 通过 SkillRegistry 路由到指定 skill，捕获传给 LLM 的工具名列表。
     */
    private List<String> captureEffectiveToolNames(
            SkillsProperties props, String activeSkill, SkillRoutingResult routingOverride) {

        SkillDefinition skillDef = props.getSkills().get(activeSkill);
        if (skillDef == null && activeSkill != null) {
            skillDef = new SkillDefinition(activeSkill, null, 0, Set.of(),
                    Set.of(), Set.of(), null, null, null, null, true);
        }
        return captureEffectiveToolNamesWithSkill(props, skillDef);
    }

    private List<String> captureEffectiveToolNamesWithSkill(
            SkillsProperties props, SkillDefinition activeSkill) {

        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.getSystemPrompt()).thenReturn("你是 Claw助手。");
        // SimpleChatClassifier: return CHAT_ONLY for the quick path test;
        // for skill tests, we send messages that need tools.
        // We bypass the quick path by making isSimpleChat return false via a tool-needing message.
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn("NEED_TOOLS");
        // Actual tool-calling response
        when(llmClient.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new LLMResponse("好的。", List.of(), "stop"));

        ContextStore contextStore = mock(ContextStore.class);
        when(contextStore.getHistory(anyInt())).thenReturn(List.of());

        // Register all tools from the SkillsProperties into ToolRegistry
        ToolRegistry registry = new ToolRegistry();
        Set<String> allToolNames = new LinkedHashSet<>();
        allToolNames.addAll(props.getGlobalTools() != null ? props.getGlobalTools() : Set.of());
        allToolNames.addAll(props.getCommonCapabilityTools() != null
                ? props.getCommonCapabilityTools() : Set.of());
        for (SkillDefinition sd : props.getSkills().values()) {
            allToolNames.addAll(sd.allowedTools());
        }
        for (String name : allToolNames) {
            registry.register(new DummyTool(name, objectMapper));
        }

        // Skill routing
        SkillRouter skillRouter = mock(SkillRouter.class);
        SkillRoutingResult routingResult = activeSkill != null
                ? SkillRoutingResult.of(activeSkill.name(), Set.of(),
                        SkillRoutingResult.SkillRoutingAction.ACTIVATE, 0.9,
                        "test routing to " + activeSkill.name())
                : SkillRoutingResult.fallback();
        when(skillRouter.route(anyString(), anyString(), anyList())).thenReturn(routingResult);

        SkillSessionStore sessionStore = mock(SkillSessionStore.class);
        SkillSession session = activeSkill != null
                ? SkillSession.create("test-user").withActiveSkill(activeSkill.name())
                : SkillSession.create("test-user");
        when(sessionStore.find(anyString())).thenReturn(Optional.of(session));

        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.find(eq("common"))).thenReturn(
                Optional.ofNullable(props.getSkills().get("common")));
        when(skillRegistry.find(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            SkillDefinition def = props.getSkills().get(name);
            return Optional.ofNullable(def);
        });

        UserExecutionContext userExecutionContext = new UserExecutionContext();

        SkillExecutionDispatcher dispatcher = mock(SkillExecutionDispatcher.class);
        when(dispatcher.dispatch(nullable(SkillDefinition.class), anyString(), any(SkillSession.class)))
                .thenAnswer(inv -> SkillExecutionResult.notHandled(inv.getArgument(2)));

        PlanStore planStore = new DefaultPlanStore();
        ToolExecutor toolExecutor = new ToolExecutor(
                registry, mock(SafetyPolicy.class), mock(SkillPendingCoordinator.class),
                mock(PendingToolCoordinator.class),
                mock(AgentActivityRecorder.class), new ToolResultStatusParser(objectMapper),
                planStore, objectMapper, mock(com.youkeda.exercise.claw.skill.SkillRegistry.class));
        ExecutionLoop executionLoop = new ExecutionLoop(
                llmClient, toolExecutor, planStore, new PlanValidator(), objectMapper,
                List.of(),
                new com.youkeda.exercise.claw.agent.runtime.SkillReplyGuardRegistry(List.of()));

        CommonCapabilityRegistry commonCapRegistry = new CommonCapabilityRegistry(props);

        ReActAgentExecutor executor = new ReActAgentExecutor(
                llmClient, registry, contextStore, objectMapper,
                planStore, mock(LongTermMemoryService.class),
                mock(com.youkeda.exercise.claw.agent.memory.ConversationSummaryService.class),
                skillRouter, sessionStore, skillRegistry, props, userExecutionContext,
                mock(SkillKnowledgeService.class),
                mock(AgentActivityRecorder.class),
                dispatcher, executionLoop, commonCapRegistry,
                notHandlingPendingCoordinator());

        // Send a message that needs tools (bypasses fast path)
        executor.execute(new AgentContext()
                .setUserId("test-user")
                .setMessage("帮我做一件事"));

        // Capture tool definitions sent to LLM
        ArgumentCaptor<List<ToolDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chatWithTools(anyString(), anyList(), captor.capture());

        // Get the last (non-empty) tools list — the one sent to the LLM for tool calling
        List<ToolDefinition> sentTools = captor.getAllValues().stream()
                .filter(list -> !list.isEmpty())
                .reduce((first, second) -> second) // last non-empty
                .orElse(List.of());

        return sentTools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toList());
    }

    /**
     * 最小工具桩：注册到 ToolRegistry 使白名单名能匹配到实际 Tool 实例。
     */
    private record DummyTool(String name, ObjectMapper om) implements Tool {
        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return "测试工具: " + name; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getParameters() {
            return om.createObjectNode().put("type", "object");
        }

        @Override
        public String execute(String argumentsJson, ToolExecutionContext context) {
            return "{\"status\":\"OK\"}";
        }
    }

    private static PendingToolCoordinator notHandlingPendingCoordinator() {
        PendingToolCoordinator mock = mock(PendingToolCoordinator.class);
        when(mock.handleUserMessage(anyString(), anyString()))
                .thenReturn(PendingToolCoordinator.Result.notHandled());
        return mock;
    }
}
