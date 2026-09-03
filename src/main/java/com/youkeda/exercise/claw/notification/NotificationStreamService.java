package com.youkeda.exercise.claw.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationStreamService {

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();

    public NotificationStreamService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        clients.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (Exception e) {
            cleanup.run();
        }
        return emitter;
    }

    public void notify(String userId, NotificationRecord notification) {
        Set<SseEmitter> emitters = clients.get(userId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : Set.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("notification")
                        .data(mapper.writeValueAsString(notification)));
            } catch (Exception e) {
                remove(userId, emitter);
            }
        }
    }

    private void remove(String userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = clients.get(userId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) clients.remove(userId, emitters);
    }
}
