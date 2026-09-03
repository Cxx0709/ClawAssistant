package com.youkeda.exercise.claw.agent.runtime;

/**
 * 用户消息对定时任务的动作意图。
 *
 * <p>由 {@link ScheduleIntentResolver} 从自然语言解析。相比旧布尔判断
 * {@code isScheduleTaskRequest}（只回答「是否创建」），保留完整动作语义，
 * 供防幻觉 guard 精确区分「创建」与「查询/修改/取消」——「我有哪些定时提醒」
 * 中的「定时」只是任务对象，不表示创建动作。
 */
public enum ScheduleIntent {
    /** 创建定时提醒 */
    CREATE,
    /** 查询提醒列表 */
    QUERY,
    /** 修改已有提醒 */
    UPDATE,
    /** 取消已有提醒 */
    DELETE,
    /** 与定时任务动作无关 */
    NONE
}
