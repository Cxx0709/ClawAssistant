package com.youkeda.exercise.claw.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "context.sqlite")
public class SqliteContextProperties {
    private boolean enabled = false;
    private String dbPath = "data/claw_assistant.db";
    private int maxMessages = 50;
    private int ttlDays = 7;
    private int busyTimeoutMs = 5000;

    // getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDbPath() { return dbPath; }
    public void setDbPath(String dbPath) { this.dbPath = dbPath; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
    public int getTtlDays() { return ttlDays; }
    public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
    public int getBusyTimeoutMs() { return busyTimeoutMs; }
    public void setBusyTimeoutMs(int busyTimeoutMs) { this.busyTimeoutMs = busyTimeoutMs; }
}
