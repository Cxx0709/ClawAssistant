package com.youkeda.exercise.claw.domain.schedule;

/**
 * 学校基本信息实体
 *
 * <p>表示一所大学/学校，可以被多个用户绑定。
 * 每个学校拥有自己独立的作息配置（{@link com.youkeda.exercise.claw.feature.schedule.SchoolScheduleConfig}）。
 *
 * <p>持久化到 SQLite {@code schools} 表，以 {@code id} 作为主键。
 */
public class SchoolEntity {

    private Long id;
    /** 学校名称，如 "无锡学院" */
    private String schoolName;
    /** 学校编码，如 "WXU" */
    private String schoolCode;
    /** 创建时间 */
    private String createdAt;

    public SchoolEntity() {
    }

    public SchoolEntity(String schoolName, String schoolCode) {
        this.schoolName = schoolName;
        this.schoolCode = schoolCode;
    }

    public SchoolEntity(Long id, String schoolName, String schoolCode) {
        this.id = id;
        this.schoolName = schoolName;
        this.schoolCode = schoolCode;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }

    public String getSchoolCode() { return schoolCode; }
    public void setSchoolCode(String schoolCode) { this.schoolCode = schoolCode; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}