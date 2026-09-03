package com.youkeda.exercise.claw.feature.schedule.pdf;

/**
 * PDF 课表表格恢复后的中间数据结构
 *
 * <p>由 {@link PdfTableExtractor} 从 PDF 坐标中恢复，
 * 包含正确的 dayOfWeek 和 period（不再由 LLM 推断）。
 *
 * <p>content 字段保留原始文本，供 LLM 提取课程名称、教师、教室、周次等。
 */
public class ScheduleCell {

    /** 星期几 1=周一 ~ 7=周日 */
    private Integer dayOfWeek;

    /** 节次范围，如 "3-4" */
    private String period;

    /** 单元格原始文本（含课程名称、周次、教师等信息） */
    private String content;

    public ScheduleCell() {
    }

    public ScheduleCell(Integer dayOfWeek, String period, String content) {
        this.dayOfWeek = dayOfWeek;
        this.period = period;
        this.content = content;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(Integer dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "ScheduleCell{dayOfWeek=" + dayOfWeek + ", period='" + period + "', content='" + content + "'}";
    }
}