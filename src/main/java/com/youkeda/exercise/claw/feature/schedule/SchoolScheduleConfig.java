package com.youkeda.exercise.claw.feature.schedule;

/**
 * 学校作息配置实体
 *
 * <p>定义学校每天的节次时间规则。每一条记录表示第几节课的起止时间。
 * 以 {@code schoolId} 作为外键，每个学校拥有一套独立的作息配置。
 *
 * <p>不再绑定 userId —— 一个学校的作息被该学校的所有用户共享。
 *
 * <p>示例：学校A 第1节 08:00-08:45，第2节 08:55-09:40，...
 */
public class SchoolScheduleConfig {

    private Long id;
    /** 所属学校 ID */
    private Long schoolId;
    /** 第几节课（从1开始） */
    private int periodNumber;
    /** 上课开始时间，格式 HH:mm */
    private String startTime;
    /** 下课结束时间，格式 HH:mm */
    private String endTime;
    /** 课时时长（分钟），可为空（自动根据 startTime 和 endTime 计算） */
    private Integer duration;

    public SchoolScheduleConfig() {
    }

    public SchoolScheduleConfig(Long schoolId, int periodNumber,
                                String startTime, String endTime, Integer duration) {
        this.schoolId = schoolId;
        this.periodNumber = periodNumber;
        this.startTime = startTime;
        this.endTime = endTime;
        this.duration = duration;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public int getPeriodNumber() { return periodNumber; }
    public void setPeriodNumber(int periodNumber) { this.periodNumber = periodNumber; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
}