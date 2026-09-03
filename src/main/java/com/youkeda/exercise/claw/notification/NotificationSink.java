package com.youkeda.exercise.claw.notification;

public interface NotificationSink {

    long publish(String userId, String source, String title, String content,
                 int priority, String actionPayload);

    int publishToAll(String source, String title, String content,
                     int priority, String actionPayload);
}
