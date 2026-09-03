package com.youkeda.exercise.claw.feature.schedule;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 考试安排实体
 *
 * <p>表示一条考试安排记录，支持按日期查询和排序。
 * 持久化到 SQLite {@code exam_schedule} 表，以 {@code userId} 作为隔离键。
 */
public class ExamEntity {

    /** 考试类型：期中考试 */
    public static final String TYPE_MIDTERM = "MIDTERM";
    /** 考试类型：期末考试 */
    public static final String TYPE_FINAL = "FINAL";
    /** 考试类型：补考 */
    public static final String TYPE_MAKEUP = "MAKEUP";

    private Long id;
    private String userId;
    private String courseName;
    private String examDate;      // yyyy-MM-dd
    private String startTime;     // HH:mm
    private String endTime;       // HH:mm
    private String location;
    private String seatNumber;
    private String examType;      // MIDTERM / FINAL / MAKEUP
    private String notes;
    private String createdTime;

    public ExamEntity() {}

    public ExamEntity(String userId, String courseName, String examDate,
                      String startTime, String endTime, String location,
                      String examType) {
        this.userId = userId;
        this.courseName = courseName;
        this.examDate = examDate;
        this.startTime = startTime != null ? startTime : "";
        this.endTime = endTime != null ? endTime : "";
        this.location = location != null ? location : "";
        this.seatNumber = "";
        this.examType = examType != null ? examType : TYPE_FINAL;
        this.notes = "";
    }

    /**
     * 获取格式化后的日期显示，如 "7月15日（周三）"
     */
    public String getDateDisplay() {
        try {
            LocalDate date = LocalDate.parse(examDate, DateTimeFormatter.ISO_LOCAL_DATE);
            String[] weekDays = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            int dow = date.getDayOfWeek().getValue();
            return date.getMonthValue() + "月" + date.getDayOfMonth() + "日（" + weekDays[dow] + "）";
        } catch (Exception e) {
            return examDate;
        }
    }

    /**
     * 获取中文考试类型
     */
    public String getExamTypeDisplay() {
        return switch (examType != null ? examType : "") {
            case TYPE_MIDTERM -> "期中考试";
            case TYPE_FINAL -> "期末考试";
            case TYPE_MAKEUP -> "补考";
            default -> examType;
        };
    }

    /** 获取时间段显示，如 "08:00-10:00" */
    public String getTimeDisplay() {
        if (startTime == null || startTime.isBlank()) return "";
        if (endTime == null || endTime.isBlank()) return startTime;
        return startTime + "-" + endTime;
    }

    /** 判断考试是否在今天之后（尚未开始） */
    public boolean isUpcoming() {
        try {
            LocalDate today = LocalDate.now();
            LocalDate exam = LocalDate.parse(examDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return !exam.isBefore(today);
        } catch (Exception e) { return false; }
    }

    /** 判断考试是否在指定天数内 */
    public boolean isWithinDays(int days) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate exam = LocalDate.parse(examDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return !exam.isBefore(today) && !exam.isAfter(today.plusDays(days));
        } catch (Exception e) { return false; }
    }

    /** 获取考试日期对应的星期几（1=周一 ~ 7=周日） */
    public int getDayOfWeek() {
        try {
            LocalDate date = LocalDate.parse(examDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.getDayOfWeek().getValue();
        } catch (Exception e) { return 0; }
    }

    // ==================== Getters & Setters ====================
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }
    public String getExamDate() { return examDate; }
    public void setExamDate(String examDate) { this.examDate = examDate; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getSeatNumber() { return seatNumber; }
    public void setSeatNumber(String seatNumber) { this.seatNumber = seatNumber; }
    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
}