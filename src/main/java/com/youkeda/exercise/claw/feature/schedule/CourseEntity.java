package com.youkeda.exercise.claw.feature.schedule;

/**
 * 课程实体
 *
 * <p>表示课表中的一条课程记录，支持周次和单双周过滤。
 * 持久化到 SQLite {@code course_schedule} 表，以 {@code userId} 作为隔离键。
 */
public class CourseEntity {

    /** 双周类型：全部周 */
    public static final String WEEK_ALL = "ALL";
    /** 双周类型：单周 */
    public static final String WEEK_ODD = "ODD";
    /** 双周类型：双周 */
    public static final String WEEK_EVEN = "EVEN";

    private Long id;
    private String userId;
    private String courseName;
    private String teacher;
    /** 星期几：1=周一 ~ 7=周日 */
    private int dayOfWeek;
    /** 开始节次（1-based） */
    private int startPeriod;
    /** 结束节次（1-based，含） */
    private int endPeriod;
    private String classroom;
    private int startWeek;
    private int endWeek;
    /** 单双周：ALL / ODD / EVEN */
    private String weekType;
    /** 所属学期 ID（nullable，兼容历史数据） */
    private Long semesterId;

    public CourseEntity() {
    }

    public CourseEntity(String userId, String courseName, String teacher,
                        int dayOfWeek, int startPeriod, int endPeriod,
                        String classroom, int startWeek, int endWeek, String weekType) {
        this.userId = userId;
        this.courseName = courseName;
        this.teacher = teacher != null ? teacher : "";
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.classroom = classroom != null ? classroom : "";
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.weekType = weekType != null ? weekType : WEEK_ALL;
    }

    /**
     * 判断课程在当前周是否上课
     *
     * @param currentWeek 当前教学周
     * @return true 表示本周有课
     */
    public boolean isActiveInWeek(int currentWeek) {
        if (currentWeek < startWeek || currentWeek > endWeek) {
            return false;
        }
        return switch (weekType) {
            case WEEK_ALL -> true;
            case WEEK_ODD -> currentWeek % 2 == 1;
            case WEEK_EVEN -> currentWeek % 2 == 0;
            default -> true;
        };
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCourseName() { return courseName; }
    public void setCourseName(String courseName) { this.courseName = courseName; }

    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }

    public int getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public int getStartPeriod() { return startPeriod; }
    public void setStartPeriod(int startPeriod) { this.startPeriod = startPeriod; }

    public int getEndPeriod() { return endPeriod; }
    public void setEndPeriod(int endPeriod) { this.endPeriod = endPeriod; }

    public String getClassroom() { return classroom; }
    public void setClassroom(String classroom) { this.classroom = classroom; }

    public int getStartWeek() { return startWeek; }
    public void setStartWeek(int startWeek) { this.startWeek = startWeek; }

    public int getEndWeek() { return endWeek; }
    public void setEndWeek(int endWeek) { this.endWeek = endWeek; }

    public String getWeekType() { return weekType; }
    public void setWeekType(String weekType) { this.weekType = weekType; }

    public Long getSemesterId() { return semesterId; }
    public void setSemesterId(Long semesterId) { this.semesterId = semesterId; }

    /** 课表显示节次范围，如 "3-4" */
    public String getPeriodDisplay() {
        return startPeriod == endPeriod
                ? String.valueOf(startPeriod)
                : startPeriod + "-" + endPeriod;
    }

    /** 周次显示，如 "1-16周(单周)" */
    public String getWeekDisplay() {
        String suffix = switch (weekType) {
            case WEEK_ODD -> "(单周)";
            case WEEK_EVEN -> "(双周)";
            default -> "";
        };
        return startWeek + "-" + endWeek + "周" + suffix;
    }

    /** 星期几中文 */
    public String getDayDisplay() {
        return switch (dayOfWeek) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "周" + dayOfWeek;
        };
    }
}