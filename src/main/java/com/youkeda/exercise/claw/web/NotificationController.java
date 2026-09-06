package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.notification.DatabaseNotificationSink;
import com.youkeda.exercise.claw.notification.NotificationRecord;
import com.youkeda.exercise.claw.notification.NotificationStreamService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final DatabaseNotificationSink notifications;
    private final NotificationStreamService streams;
    private final AuthenticatedUser authenticatedUser;

    public NotificationController(DatabaseNotificationSink notifications,
                                  NotificationStreamService streams,
                                  AuthenticatedUser authenticatedUser) {
        this.notifications = notifications;
        this.streams = streams;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping
    public List<NotificationRecord> list(Authentication authentication,
                                         @RequestParam(defaultValue = "50") int limit) {
        return notifications.list(authenticatedUser.require(authentication).id(), limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount(Authentication authentication) {
        return Map.of("count", notifications.unreadCount(authenticatedUser.require(authentication).id()));
    }

    @PostMapping("/{id}/read")
    public Map<String, Boolean> markRead(Authentication authentication, @PathVariable long id) {
        return Map.of("updated", notifications.markRead(authenticatedUser.require(authentication).id(), id));
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("updated", notifications.markAllRead(authenticatedUser.require(authentication).id()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(Authentication authentication, @PathVariable long id) {
        return Map.of("deleted", notifications.delete(authenticatedUser.require(authentication).id(), id));
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(Authentication authentication) {
        return streams.subscribe(authenticatedUser.require(authentication).id());
    }
}
