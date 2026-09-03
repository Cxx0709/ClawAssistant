package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.activity.AgentActivity;
import com.youkeda.exercise.claw.agent.activity.AgentActivityStore;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import com.youkeda.exercise.claw.feature.goal.GrowthGoal;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalRepository;
import com.youkeda.exercise.claw.identity.AppUser;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.youkeda.exercise.claw.web.conversation.Conversation;
import com.youkeda.exercise.claw.web.conversation.ConversationService;
import com.youkeda.exercise.claw.web.conversation.ConversationPage;
import com.youkeda.exercise.claw.web.conversation.ChatTranscriptService;
import com.youkeda.exercise.claw.web.conversation.MessagePage;
import com.youkeda.exercise.claw.web.conversation.TranscriptMessage;
import java.util.UUID;

@RestController
@RequestMapping("/api/webchat")
public class WebChatController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int HISTORY_MESSAGE_LIMIT = 100;

    private final ChatApplicationService chatService;
    private final WebChatStreamService streamService;
    private final AuthenticatedUser authenticatedUser;
    private final ContextStore contextStore;
    private final GrowthGoalRepository goalRepository;
    private final AgentActivityStore activityStore;
    private final LongTermMemoryService memoryService;
    private final ConversationService conversationService;
    private final ChatTranscriptService transcriptService;

    public WebChatController(ChatApplicationService chatService,
                             WebChatStreamService streamService,
                             AuthenticatedUser authenticatedUser,
                             ContextStore contextStore,
                             GrowthGoalRepository goalRepository,
                             AgentActivityStore activityStore,
                             LongTermMemoryService memoryService,
                             ConversationService conversationService,
                             ChatTranscriptService transcriptService) {
        this.chatService = chatService;
        this.streamService = streamService;
        this.authenticatedUser = authenticatedUser;
        this.contextStore = contextStore;
        this.goalRepository = goalRepository;
        this.activityStore = activityStore;
        this.memoryService = memoryService;
        this.conversationService = conversationService;
        this.transcriptService = transcriptService;
    }

    @PostMapping("/send")
    public Map<String, Object> send(Authentication authentication, @RequestBody ChatRequest body) {
        AppUser user = authenticatedUser.require(authentication);
        String conversationId = resolveConversation(user.id(), body.conversationId());
        String runId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        ChatTranscriptService.RunStart run = transcriptService.start(
                user.id(), conversationId, body.message(), body.attachmentIds(), runId);
        ChatResponse response;
        try {
            response = chatService.execute(
                    user.id(), conversationId, body.message(), body.attachmentIds(), runId, null);
            transcriptService.complete(user.id(), runId, response.reply(), List.of(), List.of(),
                    response.artifacts(), System.currentTimeMillis() - startedAt);
        } catch (RuntimeException error) {
            transcriptService.fail(user.id(), runId, "", List.of(), List.of(), error.getMessage(),
                    System.currentTimeMillis() - startedAt);
            throw error;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("reply", response.reply());
        result.put("artifacts", response.artifacts());
        result.put("silent", response.silent());
        result.put("conversationId", conversationId);
        result.put("runId", run.runId());
        return result;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(Authentication authentication, @RequestBody ChatRequest body) {
        AppUser user = authenticatedUser.require(authentication);
        String conversationId = resolveConversation(user.id(), body.conversationId());
        SseEmitter emitter = new SseEmitter(0L);
        streamService.streamMessage(
                emitter, user.id(), conversationId, body.message(), body.attachmentIds());
        return emitter;
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(
            Authentication authentication,
            @RequestParam(required = false) String conversationId) {
        String userId = authenticatedUser.require(authentication).id();
        String resolved = resolveConversation(userId, conversationId);
        return toHistoryItems(contextStore.getHistory(
                userId, resolved, HISTORY_MESSAGE_LIMIT * 5));
    }

    @GetMapping("/conversations")
    public ConversationPage conversations(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int limit) {
        return conversationService.page(
                authenticatedUser.require(authentication).id(), archived, deleted, q, cursor, limit);
    }

    @PostMapping("/conversations")
    public Conversation createConversation(Authentication authentication) {
        return conversationService.create(authenticatedUser.require(authentication).id());
    }

    @GetMapping("/conversations/{id}/messages")
    public MessagePage conversationMessages(
            Authentication authentication, @PathVariable String id,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        String userId = authenticatedUser.require(authentication).id();
        conversationService.requireOwned(userId, id);
        return transcriptService.page(userId, id, before, limit);
    }

    @GetMapping("/runs/{id}")
    public TranscriptMessage run(Authentication authentication, @PathVariable String id) {
        return transcriptService.findByRun(authenticatedUser.require(authentication).id(), id);
    }

    @PatchMapping("/conversations/{id}")
    public Conversation updateConversation(
            Authentication authentication,
            @PathVariable String id,
            @RequestBody ConversationUpdate body) {
        return conversationService.update(authenticatedUser.require(authentication).id(),
                id, body.title(), body.pinned(), body.archived(), body.deleted());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(
            Authentication authentication, @PathVariable String id) {
        conversationService.delete(authenticatedUser.require(authentication).id(), id);
        return Map.of("status", "SUCCESS");
    }

    @DeleteMapping("/conversations/{id}/purge")
    public Map<String, Object> purgeConversation(
            Authentication authentication, @PathVariable String id) {
        conversationService.purge(authenticatedUser.require(authentication).id(), id);
        return Map.of("status", "SUCCESS");
    }

    @GetMapping("/conversations/{id}/export")
    public ResponseEntity<Map<String, Object>> exportConversation(
            Authentication authentication, @PathVariable String id) {
        String userId = authenticatedUser.require(authentication).id();
        Conversation conversation = conversationService.requireOwned(userId, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("conversation", conversation);
        payload.put("messages", transcriptService.all(userId, id));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=claw-conversation-" + id + ".json")
                .body(payload);
    }

    @PostMapping("/conversations/import")
    public Conversation importConversation(
            Authentication authentication, @RequestBody ConversationImport body) {
        String userId = authenticatedUser.require(authentication).id();
        Conversation created = conversationService.create(userId);
        if (body.title() != null && !body.title().isBlank()) {
            created = conversationService.update(userId, created.id(), body.title(), null, null);
        }
        int count = transcriptService.importMessages(userId, created.id(), body.messages());
        if (count > 0) {
            String preview = body.messages().stream()
                    .filter(item -> item != null && "user".equals(item.role()))
                    .map(ChatTranscriptService.ImportMessage::content)
                    .findFirst().orElse("已导入 " + count + " 条消息");
            conversationService.touchAfterMessage(userId, created.id(), preview);
        }
        return conversationService.requireOwned(userId, created.id());
    }

    static List<Map<String, Object>> toHistoryItems(List<Message> history) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message message : history) {
            boolean visible = message.role() == MessageRole.USER
                    || (message.role() == MessageRole.ASSISTANT && !message.isToolCall());
            if (!visible) continue;
            result.add(Map.of("role", message.role().value(), "content", message.content()));
        }
        int fromIndex = Math.max(0, result.size() - HISTORY_MESSAGE_LIMIT);
        return List.copyOf(result.subList(fromIndex, result.size()));
    }

    @PostMapping("/clear")
    public Map<String, Object> clear(
            Authentication authentication,
            @RequestParam(required = false) String conversationId) {
        String userId = authenticatedUser.require(authentication).id();
        String resolved = resolveConversation(userId, conversationId);
        contextStore.clear(userId, resolved);
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

    private String resolveConversation(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return conversationService.ensureDefault(userId).id();
        }
        return conversationService.requireOwned(userId, conversationId).id();
    }

    public record ChatRequest(String message, List<String> attachmentIds, String conversationId) {
        public ChatRequest {
            if (attachmentIds == null) attachmentIds = List.of();
        }
    }

    public record ConversationUpdate(String title, Boolean pinned, Boolean archived, Boolean deleted) {}
    public record ConversationImport(String title, List<ChatTranscriptService.ImportMessage> messages) {}
}
