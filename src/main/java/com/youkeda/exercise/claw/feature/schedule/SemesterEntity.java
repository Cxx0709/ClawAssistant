package com.youkeda.exercise.claw.feature.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 学期实体
 *
 * <p>表示一个学年学期，每个用户可以有多个学期记录。
 * 持久化到 SQLite {@code semester} 表，以 {@code userId} 作为隔离键。
 *
 * <p>学期决定了课程表第一周周一的日期，后续所有周次计算均基于此。
 */
public class SemesterEntity {

    /** 学期类型：春季（3月1日所在周为第1周） */
    public static final String TERM_SPRING = "SPRING";
    /** 学期类型：秋季（9月1日所在周为第1周） */
    public static final String TERM_FALL = "FALL";

    /** 学期来源：用户确认 */
    public static final String SOURCE_USER_CONFIRM = "USER_CONFIRM";
    /** 学期来源：自动检测（从文件名/日期推断） */
    public static final String SOURCE_AUTO_DETECT = "AUTO_DETECT";
    /** 学期来源：系统默认 */
    public static final String SOURCE_SYSTEM_DEFAULT = "SYSTEM_DEFAULT";

    private Long id;
    private String userId;
    private int academicYear;
    private String term;
    private LocalDate startDate;
    private String source;
    private String createdTime;

    public SemesterEntity() {
    }

    public SemesterEntity(String userId, int academicYear, String term,
                          LocalDate startDate, String source) {
        this.userId = userId;
        this.academicYear = academicYear;
        this.term = term;
        this.startDate = startDate;
        this.source = source != null ? source : SOURCE_AUTO_DETECT;
    }

    /**
     * 获取学期显示名称，如 "2026秋季学期"
     */
    public String getDisplayName() {
        return academicYear + getTermDisplay();
    }

    /**
     * 获取中文学期类型
     */
    public String getTermDisplay() {
        return TERM_FALL.equals(term) ? "秋季学期" : "春季学期";
    }

    /**
     * 获取学期起始日格式化显示，如 "2026年9月7日（周一）"
     */
    public String getStartDateDisplay() {
        if (startDate == null) return "";
        String[] weekDays = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int dow = startDate.getDayOfWeek().getValue();
        return startDate.getYear() + "年"
                + startDate.getMonthValue() + "月"
                + startDate.getDayOfMonth() + "日（"
                + weekDays[dow] + "）";
    }

    /**
     * 获取学期来源中文描述
     */
    public String getSourceDisplay() {
        return switch (source) {
            case SOURCE_USER_CONFIRM -> "用户确认";
            case SOURCE_AUTO_DETECT -> "自动检测";
            case SOURCE_SYSTEM_DEFAULT -> "系统默认";
            default -> source;
        };
    }

    /**
     * 计算当前教学周
     *
     * @return 当前教学周，学期未开始返回 -1
     */
    public int getCurrentWeek() {
        if (startDate == null) return -1;
        LocalDate today = LocalDate.now();
        if (today.isBefore(startDate)) {
            return -1;
        }
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, today);
        return (int) (daysBetween / 7) + 1;
    }

    /**
     * 判断当前周是否为单周
     */
    public boolean isOddWeek() {
        int week = getCurrentWeek();
        return week > 0 && week % 2 == 1;
    }

    /**
     * 判断当前周是否为双周
     */
    public boolean isEvenWeek() {
        int week = getCurrentWeek();
        return week > 0 && week % 2 == 0;
    }

    /**
     * 获取 startDate 的字符串形式（yyyy-MM-dd），用于持久化
     */
    public String getStartDateString() {
        return startDate != null ? startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) : "";
    }

    /**
     * 从字符串设置 startDate（yyyy-MM-dd）
     */
    public void setStartDateFromString(String dateStr) {
        this.startDate = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                : null;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }

    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
}