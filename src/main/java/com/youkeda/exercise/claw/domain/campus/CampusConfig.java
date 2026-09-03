package com.youkeda.exercise.claw.domain.campus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class CampusConfig {
    private Long id;
    private String school;
    private String className;
    private boolean enabled = true;
    private ExamPreferences preferences = new ExamPreferences();
    private Instant createdAt;
    private Instant updatedAt;

    // 新增：各 Source 独立开关，key=source名称，value=是否开启
    private Map<String, Boolean> sourceEnabled = new HashMap<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ExamPreferences getPreferences() { return preferences; }
    public void setPreferences(ExamPreferences preferences) { this.preferences = preferences; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isSourceEnabled(String sourceName) {
        return sourceEnabled.getOrDefault(sourceName, true);
    }

    public void setSourceEnabled(String sourceName, boolean enabled) {
        sourceEnabled.put(sourceName, enabled);
    }
}
