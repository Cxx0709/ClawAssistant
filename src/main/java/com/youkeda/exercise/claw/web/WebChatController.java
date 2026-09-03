package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.activity.AgentActivity;
import com.youkeda.exercise.claw.agent.activity.AgentActivityStore;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import com.youkeda.exercise.claw.feature.goal.GrowthGoal;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalRepository;
import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webchat")
public class WebChatController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatApplicationService chatService;
    private final WebChatStreamService streamService;
    private final AuthenticatedUser authenticatedUser;
    private final ContextStore contextStore;
    private final GrowthGoalRepository goalRepository;
    private final AgentActivityStore activityStore;
    private final LongTermMemoryService memoryService;

    public WebChatController(ChatApplicationService chatService,
                             WebChatStreamService streamService,
                             AuthenticatedUser authenticatedUser,
                             ContextStore contextStore,
                             GrowthGoalRepository goalRepository,
                             AgentActivityStore activityStore,
                             LongTermMemoryService memoryService) {
        this.chatService = chatService;
        this.streamService = streamService;
        this.authenticatedUser = authenticatedUser;
        this.contextStore = contextStore;
        this.goalRepository = goalRepository;
        this.activityStore = activityStore;
        this.memoryService = memoryService;
    }

    @PostMapping("/send")
    public Map<String, Object> send(Authentication authentication, @RequestBody ChatRequest body) {
        AppUser user = authenticatedUser.require(authentication);
        ChatResponse response = chatService.execute(
                user.id(), body.message(), body.attachmentIds(), null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("reply", response.reply());
        result.put("artifacts", response.artifacts());
        result.put("silent", response.silent());
        return result;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(Authentication authentication, @RequestBody ChatRequest body) {
        AppUser user = authenticatedUser.require(authentication);
        SseEmitter emitter = new SseEmitter(0L);
        streamService.streamMessage(emitter, user.id(), body.message(), body.attachmentIds());
        return emitter;
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message message : contextStore.getHistory(userId, 100)) {
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) continue;
            result.add(Map.of("role", message.role(), "content", message.content()));
        }
        return result;
    }

    @PostMapping("/clear")
    public Map<String, Object> clear(Authentication authentication) {
        contextStore.clear(authenticatedUser.require(authentication).id());
        return Map.of("status", "SUCCESS");
    }

    @GetMapping("/goals")
    public List<Map<String, Object>> goals(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        return goalRepository.findByUser(userId, GrowthGoal.Status.ACTIVE).stream().map(g -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", g.id());
            item.put("title", g.title());
            item.put("successCriteria", g.successCriteria());
            item.put("deadline", g.deadline());
            item.put("status", g.status().name());
            item.put("progress", g.progress());
            return item;
        }).toList();
    }

    @GetMapping("/memories")
    public List<Map<String, Object>> memories(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        try {
            return memoryService.listAll(userId).stream().map(this::memoryMap).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @GetMapping("/activities")
    public List<Map<String, Object>> activities(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        return activityStore.findRecent(userId, 20).stream().map(this::activityMap).toList();
    }

    @GetMapping("/status")
    public Map<String, Object> status(Authentication authentication) {
        String userId = authenticatedUser.require(authentication).id();
        return Map.of("appReady", true,
                "activeGoalCount", goalRepository.findByUser(userId, GrowthGoal.Status.ACTIVE).size());
    }

    private Map<String, Object> memoryMap(MemoryItem memory) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", memory.id());
        item.put("category", memory.category().name());
        item.put("content", memory.content());
        item.put("topicKey", memory.topicKey());
        item.put("importance", memory.importance());
        return item;
    }

    private Map<String, Object> activityMap(AgentActivity activity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", activity.createdAt().atZone(ZoneId.systemDefault()).format(TIME_FMT));
        item.put("text", activity.summary() == null ? activity.eventType().name() : activity.summary());
        item.put("color", switch (activity.eventType()) {
            case SKILL_SELECTED -> "purple";
            case TOOL_SUCCEEDED -> "green";
            case TOOL_FAILED -> "orange";
            default -> "blue";
        });
        return item;
    }

    public record ChatRequest(String message, List<String> attachmentIds) {
        public ChatRequest {
            if (attachmentIds == null) attachmentIds = List.of();
        }
    }
}
