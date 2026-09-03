package com.youkeda.exercise.claw.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.AgentStreamObserver;
import com.youkeda.exercise.claw.agent.activity.AgentActivityEvent;
import com.youkeda.exercise.claw.agent.activity.AgentActivityRecorder;
import com.youkeda.exercise.claw.web.conversation.ChatTranscriptService;
import com.youkeda.exercise.claw.web.conversation.ToolTraceItem;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
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
    private final ChatTranscriptService transcriptService;
    private final ExecutorService executor;

    public WebChatStreamService(ChatApplicationService chatService,
                                AgentActivityRecorder activityRecorder,
                                ObjectMapper objectMapper,
                                ChatTranscriptService transcriptService) {
        this.chatService = chatService;
        this.activityRecorder = activityRecorder;
        this.objectMapper = objectMapper;
        this.transcriptService = transcriptService;
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "webchat-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void streamMessage(SseEmitter emitter, String userId, String conversationId, String message,
                              List<String> attachmentIds) {
        executor.execute(() -> run(emitter, userId, conversationId, message, attachmentIds));
    }

    private void run(SseEmitter emitter, String userId, String conversationId, String message,
                     List<String> attachmentIds) {
        boolean hasAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        StreamSink sink = new StreamSink(emitter, userId, UUID.randomUUID().toString());
        if ((message == null || message.isBlank()) && !hasAttachments) {
            sink.error("消息或附件不能为空");
            sink.complete();
            return;
        }
        String requestId = sink.runId;
        long startedAt = System.currentTimeMillis();
        try {
            ChatTranscriptService.RunStart run = transcriptService.start(
                    userId, conversationId, message, attachmentIds, requestId);
            sink.send(Map.of("type", "run", "runId", run.runId(),
                    "userMessageId", run.userMessageId(),
                    "assistantMessageId", run.assistantMessageId()));
        } catch (Exception e) {
            sink.error(e.getMessage() == null ? "无法保存消息" : e.getMessage());
            sink.complete();
            return;
        }
        AgentActivityRecorder.Subscription subscription =
                activityRecorder.subscribe(requestId, sink::onActivity);
        AgentStreamObserver observer = sink::onContentDelta;
        try {
            ChatResponse response = chatService.execute(
                    userId, conversationId, message == null ? "" : message,
                    attachmentIds, requestId, observer);
            try {
                transcriptService.complete(userId, requestId, response.reply(), sink.tools,
                        sink.skills, response.artifacts(), System.currentTimeMillis() - startedAt);
            } catch (Exception persistenceError) {
                log.error("保存完整回复失败 | requestId={}", requestId, persistenceError);
            }
            sink.done(response);
        } catch (Exception e) {
            log.error("WebChat 流式失败 | requestId={} | userId={}", requestId, userId, e);
            try {
                transcriptService.fail(userId, requestId, sink.content.toString(), sink.tools,
                        sink.skills, e.getMessage(), System.currentTimeMillis() - startedAt);
            } catch (Exception persistenceError) {
                log.error("保存失败状态失败 | requestId={}", requestId, persistenceError);
            }
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
        private final AtomicBoolean emitterClosed = new AtomicBoolean(false);
        private final AtomicBoolean terminalSent = new AtomicBoolean(false);
        private final String userId;
        private final String runId;
        private final StringBuilder content = new StringBuilder();
        private final List<ToolTraceItem> tools = new ArrayList<>();
        private final List<String> skills = new ArrayList<>();
        private int toolSequence;
        private long lastCheckpointAt;

        private StreamSink(SseEmitter emitter, String userId, String runId) {
            this.emitter = emitter;
            this.userId = userId;
            this.runId = runId;
            emitter.onCompletion(this::markClosed);
            emitter.onTimeout(this::markClosed);
            emitter.onError(error -> markClosed());
        }

        private void onActivity(AgentActivityEvent event) {
            switch (event.eventType()) {
                case SKILL_SELECTED -> {
                    String skill = safe(event.skillName());
                    if (!skill.isBlank() && !skills.contains(skill)) skills.add(skill);
                    send(Map.of("type", "skill", "name", skill));
                    checkpoint(false);
                }
                case TOOL_STARTED -> {
                    tools.add(new ToolTraceItem("t" + (++toolSequence), safe(event.toolName()),
                            safe(event.skillName()), "running", null, null));
                    send(Map.of("type", "tool_start", "name", safe(event.toolName()),
                            "skill", safe(event.skillName())));
                    checkpoint(false);
                }
                case TOOL_SUCCEEDED -> {
                    finishTool(event, true);
                    send(Map.of("type", "tool_end", "name", safe(event.toolName()),
                        "skill", safe(event.skillName()), "ok", true,
                        "durationMs", event.durationMs() == null ? 0L : event.durationMs()));
                    checkpoint(true);
                }
                case TOOL_FAILED, TOOL_BLOCKED -> {
                    finishTool(event, false);
                    send(Map.of("type", "tool_end",
                        "name", safe(event.toolName()), "skill", safe(event.skillName()), "ok", false,
                        "durationMs", event.durationMs() == null ? 0L : event.durationMs(),
                        "detail", safe(event.summary())));
                    checkpoint(true);
                }
                default -> { }
            }
        }

        private void onContentDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                content.append(delta);
                send(Map.of("type", "text", "content", delta));
                checkpoint(false);
            }
        }

        private void finishTool(AgentActivityEvent event, boolean ok) {
            for (int i = tools.size() - 1; i >= 0; i--) {
                ToolTraceItem tool = tools.get(i);
                if (tool.state().equals("running") && tool.name().equals(safe(event.toolName()))) {
                    tools.set(i, new ToolTraceItem(tool.id(), tool.name(), tool.skill(),
                            ok ? "ok" : "err", event.durationMs(), safe(event.summary())));
                    return;
                }
            }
        }

        private void checkpoint(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastCheckpointAt < 250 && content.length() % 256 != 0) return;
            lastCheckpointAt = now;
            try {
                transcriptService.checkpoint(userId, runId, content.toString(),
                        List.copyOf(tools), List.copyOf(skills));
            } catch (Exception e) {
                log.warn("保存生成进度失败 | runId={}", runId, e);
            }
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
            if (!terminalSent.compareAndSet(false, true)) return;
            sendUnchecked(payload);
        }

        private void send(Map<String, ?> payload) {
            if (!terminalSent.get()) sendUnchecked(payload);
        }

        private void sendUnchecked(Map<String, ?> payload) {
            if (emitterClosed.get()) return;
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
            emitterClosed.set(true);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
