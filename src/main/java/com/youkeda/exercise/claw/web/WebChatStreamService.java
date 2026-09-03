package com.youkeda.exercise.claw.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.AgentStreamObserver;
import com.youkeda.exercise.claw.agent.activity.AgentActivityEvent;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-request SSE bridge for the channel-neutral chat application service. */
@Service
public class WebChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(WebChatStreamService.class);
    private static final int POOL_SIZE = 4;

    private final ChatApplicationService chatService;
    private final AgentActivityRecorder activityRecorder;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public WebChatStreamService(ChatApplicationService chatService,
                                AgentActivityRecorder activityRecorder,
                                ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.activityRecorder = activityRecorder;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "webchat-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void streamMessage(SseEmitter emitter, String userId, String message,
                              List<String> attachmentIds) {
        executor.execute(() -> run(emitter, userId, message, attachmentIds));
    }

    private void run(SseEmitter emitter, String userId, String message, List<String> attachmentIds) {
        StreamSink sink = new StreamSink(emitter);
        boolean hasAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        if ((message == null || message.isBlank()) && !hasAttachments) {
            sink.error("消息或附件不能为空");
            sink.complete();
            return;
        }
        String requestId = UUID.randomUUID().toString();
        AgentActivityRecorder.Subscription subscription =
                activityRecorder.subscribe(requestId, sink::onActivity);
        AgentStreamObserver observer = sink::onContentDelta;
        try {
            ChatResponse response = chatService.execute(
                    userId, message == null ? "" : message, attachmentIds, requestId, observer);
            sink.done(response);
        } catch (Exception e) {
            log.error("WebChat 流式失败 | requestId={} | userId={}", requestId, userId, e);
            sink.error(e.getMessage() == null ? "处理失败" : e.getMessage());
        } finally {
            subscription.cancel();
            sink.complete();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private final class StreamSink {
        private final SseEmitter emitter;
        private final AtomicBoolean done = new AtomicBoolean(false);

        private StreamSink(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(this::markClosed);
            emitter.onTimeout(this::markClosed);
            emitter.onError(error -> markClosed());
        }

        private void onActivity(AgentActivityEvent event) {
            switch (event.eventType()) {
                case SKILL_SELECTED -> send(Map.of("type", "skill", "name", safe(event.skillName())));
                case TOOL_STARTED -> send(Map.of("type", "tool_start", "name", safe(event.toolName()),
                        "skill", safe(event.skillName())));
                case TOOL_SUCCEEDED -> send(Map.of("type", "tool_end", "name", safe(event.toolName()),
                        "skill", safe(event.skillName()), "ok", true,
                        "durationMs", event.durationMs() == null ? 0L : event.durationMs()));
                case TOOL_FAILED, TOOL_BLOCKED -> send(Map.of("type", "tool_end",
                        "name", safe(event.toolName()), "skill", safe(event.skillName()), "ok", false,
                        "durationMs", event.durationMs() == null ? 0L : event.durationMs(),
                        "detail", safe(event.summary())));
                default -> { }
            }
        }

        private void onContentDelta(String delta) {
            if (delta != null && !delta.isEmpty()) send(Map.of("type", "text", "content", delta));
        }

        private void done(ChatResponse response) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "done");
            payload.put("reply", response.reply());
            payload.put("silent", response.silent());
            payload.put("artifacts", response.artifacts());
            sendTerminal(payload);
        }

        private void error(String message) {
            sendTerminal(Map.of("type", "error", "message", message));
        }

        private void sendTerminal(Map<String, ?> payload) {
            if (!done.compareAndSet(false, true)) return;
            sendUnchecked(payload);
        }

        private void send(Map<String, ?> payload) {
            if (!done.get()) sendUnchecked(payload);
        }

        private void sendUnchecked(Map<String, ?> payload) {
            try {
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
            } catch (Exception e) {
                markClosed();
            }
        }

        private void complete() {
            try { emitter.complete(); } catch (Exception ignored) { }
        }

        private void markClosed() {
            done.set(true);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
