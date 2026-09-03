package com.youkeda.exercise.claw.feature.schedule;

/**
 * 课间休息配置实体
 *
 * <p>定义在特定节次之后的课间休息时长和类型。
 * 以 {@code schoolId} 作为外键，每个学校独立配置课间休息规则。
 *
 * <p>例如：第2节结束后休息20分钟（大课间），其他节次间休息5分钟（小课间）。
 *
 * <p>用于课程提醒、空闲时间计算、推荐活动时间等场景。
 */
public class BreakConfig {

    /** 课间类型：小课间（通常 5-10 分钟） */
    public static final String BREAK_SHORT = "SHORT";
    /** 课间类型：大课间（通常 15-30 分钟） */
    public static final String BREAK_LONG = "LONG";

    private Long id;
    /** 所属学校 ID */
    private Long schoolId;
    /** 在哪节课之后休息（如 2 表示第2节之后） */
    private int afterPeriod;
    /** 休息时长（分钟） */
    private int breakDuration;
    /** 课间类型：SHORT / LONG */
    private String breakType;

    public BreakConfig() {
    }

    public BreakConfig(Long schoolId, int afterPeriod, int breakDuration, String breakType) {
        this.schoolId = schoolId;
        this.afterPeriod = afterPeriod;
        this.breakDuration = breakDuration;
        this.breakType = breakType != null ? breakType : BREAK_SHORT;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public int getAfterPeriod() { return afterPeriod; }
    public void setAfterPeriod(int afterPeriod) { this.afterPeriod = afterPeriod; }

    public int getBreakDuration() { return breakDuration; }
    public void setBreakDuration(int breakDuration) { this.breakDuration = breakDuration; }

    public String getBreakType() { return breakType; }
    public void setBreakType(String breakType) { this.breakType = breakType; }
}
