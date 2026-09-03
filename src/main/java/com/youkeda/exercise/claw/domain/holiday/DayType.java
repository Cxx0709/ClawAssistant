package com.youkeda.exercise.claw.domain.holiday;

/**
 * 日期类型枚举
 *
 * <p>标识一个日期在中国法定节假日体系中的类型。
 * 用于 {@link HolidayCheckFunction} 的返回结果。
 */
public enum DayType {

    /** 法定节假日（如国庆节、春节等） */
    HOLIDAY,

    /** 调休工作日（周末上班，为节假日调休） */
    SWAP_WORKDAY,

    /** 普通周末（周六或周日，非调休） */
    WEEKEND,

    /** 普通工作日（周一至周五，非调休） */
    WEEKDAY
}
