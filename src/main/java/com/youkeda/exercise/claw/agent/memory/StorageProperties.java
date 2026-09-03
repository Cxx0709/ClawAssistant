package com.youkeda.exercise.claw.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置（SQLite / 内存切换）
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** 是否启用 SQLite 存储（false 时用内存存储） */
    private boolean enabled = false;

    /** 消息过期天数 */
    private int ttlDays = 7;

    /** 单用户最大消息条数 */
    private int maxMessages = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(int ttlDays) {
        this.ttlDays = ttlDays;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }
}
