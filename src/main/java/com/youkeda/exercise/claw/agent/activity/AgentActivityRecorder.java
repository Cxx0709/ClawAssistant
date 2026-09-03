package com.youkeda.exercise.claw.agent.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AgentActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityRecorder.class);

    private final AgentActivityStore store;

    /** 实时订阅（Web 流式用）：requestId → 监听该请求活动的消费者集合。 */
    private final Map<String, Set<Consumer<AgentActivityEvent>>> listeners = new ConcurrentHashMap<>();

    public AgentActivityRecorder(AgentActivityStore store) {
        this.store = store;
    }

    public String beginRequest() {
        return beginRequest(UUID.randomUUID().toString());
    }

    /**
     * 以外部预生成的 requestId 开始一次请求记录（流式场景：入口在调用 agent 前需先订阅，
     * 故 requestId 必须预先确定）。记录一条 {@code REQUEST_RECEIVED} 后原样返回。
     */
    public String beginRequest(String externalRequestId) {
        String requestId = Objects.requireNonNull(externalRequestId, "externalRequestId");
        record(new AgentActivityEvent(
                requestId, ActivityEventType.REQUEST_RECEIVED,
                null, null, "RUNNING", "收到新请求", null));
        return requestId;
    }

    /**
     * 订阅指定 requestId 的实时活动事件。返回的 {@link Subscription} 需在请求结束时取消，
     * 避免泄漏。事件与落库同序回调（在 agent 执行线程上），消费者不应做耗时阻塞操作。
     */
    public Subscription subscribe(String requestId, Consumer<AgentActivityEvent> consumer) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(consumer, "consumer");
        listeners.computeIfAbsent(requestId, k -> ConcurrentHashMap.newKeySet()).add(consumer);
        return new Subscription(requestId, consumer);
    }

    public void skillSelected(String requestId, String skillName) {
        record(new AgentActivityEvent(
                requestId, ActivityEventType.SKILL_SELECTED,
                skillName, null, "SUCCESS", "选择 " + safeName(skillName) + " Skill", null));
    }

    public void toolStarted(String requestId, String skillName, String toolName) {
        record(new AgentActivityEvent(
                requestId, ActivityEventType.TOOL_STARTED,
                skillName, toolName, "RUNNING", "开始执行工具", null));
    }

    public void toolFinished(String requestId, String skillName, String toolName,
                             boolean success, long durationMs) {
        toolFinished(requestId, skillName, toolName, success, durationMs, null);
    }

    public void toolFinished(String requestId, String skillName, String toolName,
                             boolean success, long durationMs, String detail) {
        String summary = success ? "工具执行完成"
                : (detail == null || detail.isBlank() ? "工具执行失败" : safeReason(detail));
        record(new AgentActivityEvent(
                requestId,
                success ? ActivityEventType.TOOL_SUCCEEDED : ActivityEventType.TOOL_FAILED,
                skillName, toolName, success ? "SUCCESS" : "FAILED",
                summary,
                Math.max(0L, durationMs)));
    }

    public void toolBlocked(String requestId, String skillName, String toolName, String reason) {
        record(new AgentActivityEvent(
                requestId, ActivityEventType.TOOL_BLOCKED,
                skillName, toolName, "BLOCKED", safeReason(reason), null));
    }

    public void requestCompleted(String requestId, long durationMs) {
        record(new AgentActivityEvent(
                requestId, ActivityEventType.RESPONSE_COMPLETED,
                null, null, "SUCCESS", "回复已完成", Math.max(0L, durationMs)));
    }

    public void requestFailed(String requestId, String reason, long durationMs) {
        record(new AgentActivityEvent(
                requestId, ActivityEventType.REQUEST_FAILED,
                null, null, "FAILED", safeReason(reason), Math.max(0L, durationMs)));
    }

    private void record(AgentActivityEvent event) {
        try {
            store.record(event);
        } catch (RuntimeException e) {
            log.warn("记录 Agent 活动失败 | type={} | requestId={}",
                    event.eventType(), event.requestId(), e);
        }
        // 实时推送给该请求的订阅者（失败仅告警，不影响主链路与落库）
        Set<Consumer<AgentActivityEvent>> subscribers = listeners.get(event.requestId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Consumer<AgentActivityEvent> consumer : subscribers) {
            try {
                consumer.accept(event);
            } catch (RuntimeException e) {
                log.warn("推送 Agent 活动失败 | type={} | requestId={}",
                        event.eventType(), event.requestId(), e);
            }
        }
    }

    private void unsubscribe(String requestId, Consumer<AgentActivityEvent> consumer) {
        Set<Consumer<AgentActivityEvent>> subscribers = listeners.get(requestId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(consumer);
        if (subscribers.isEmpty()) {
            listeners.remove(requestId);
        }
    }

    /**
     * 订阅句柄。{@link #cancel()} 使 recorder 不再向该消费者推送事件（幂等）。
     */
    public final class Subscription {

        private final String requestId;
        private final Consumer<AgentActivityEvent> consumer;
        private volatile boolean cancelled;

        private Subscription(String requestId, Consumer<AgentActivityEvent> consumer) {
            this.requestId = requestId;
            this.consumer = consumer;
        }

        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            unsubscribe(requestId, consumer);
        }
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "common" : value;
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return "未提供原因";
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }
}
