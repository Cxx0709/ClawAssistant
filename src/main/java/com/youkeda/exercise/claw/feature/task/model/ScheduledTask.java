package com.youkeda.exercise.claw.feature.task.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 定时任务实体
 *
 * <p>表示一条用户通过自然语言创建的定时提醒任务。
 * 支持一次性任务和周期任务（DAILY / WEEKLY / MONTHLY），
 * 通过 {@link #repeatType} 区分。
 *
 * <p>调度器使用 {@link #nextExecuteTime} 判断是否到期，
 * 执行后通过 {@link com.youkeda.exercise.claw.feature.task.service.RepeatCalculator} 计算下次执行时间。
 *
 * <p>状态流转：
 * <pre>
 *   ACTIVE ──执行──→ DONE（一次性，repeatType=NONE 或 ONCE）
 *   ACTIVE ──执行──→ ACTIVE（周期任务，更新 next_execute_time）
 *   ACTIVE ──取消──→ CANCELLED
 *   ACTIVE ──失败──→ FAILED
 *   ACTIVE ──暂停──→ PAUSED
 *   PAUSED ──恢复──→ ACTIVE
 * </pre>
 */
public class ScheduledTask {

    // ==================== 任务类型 ====================

    /** 普通提醒任务 */
    public static final String TASK_TYPE_REMINDER = "REMINDER";
    /** Agent 自动执行任务 */
    public static final String TASK_TYPE_AGENT = "AGENT";

    /** 所有任务类型集合 */
    public static final java.util.Set<String> TASK_TYPES = java.util.Set.of(
            TASK_TYPE_REMINDER, TASK_TYPE_AGENT
    );

    // ==================== 触发类型 ====================

    /** 延迟触发（旧版兼容） */
    public static final String TRIGGER_TYPE_DELAY = "DELAY";

    // ==================== 周期类型 ====================

    /** 无重复（一次性），规范化名称 */
    public static final String REPEAT_TYPE_NONE = "NONE";
    /** 一次性（兼容旧数据） */
    public static final String REPEAT_TYPE_ONCE = "ONCE";
    /** 每日 */
    public static final String REPEAT_TYPE_DAILY = "DAILY";
    /** 每周 */
    public static final String REPEAT_TYPE_WEEKLY = "WEEKLY";
    /** 每月 */
    public static final String REPEAT_TYPE_MONTHLY = "MONTHLY";

    /** 所有周期类型集合（用于校验） */
    public static final java.util.Set<String> REPEAT_TYPES = java.util.Set.of(
            REPEAT_TYPE_NONE, REPEAT_TYPE_ONCE,
            REPEAT_TYPE_DAILY, REPEAT_TYPE_WEEKLY, REPEAT_TYPE_MONTHLY
    );

    // ==================== 任务状态 ====================

    /** 待执行 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 执行中（防重复提交，P0-2） */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 已完成（一次性任务） */
    public static final String STATUS_DONE = "DONE";
    /** 已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 执行失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 已暂停（仅 Agent 任务支持） */
    public static final String STATUS_PAUSED = "PAUSED";

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String userId;
    private String content;
    private String triggerType;
    private LocalDateTime executeTime;
    private String repeatType;
    private Integer repeatInterval;
    private LocalDateTime nextExecuteTime;
    private String status;
    private String taskType;
    private LocalDateTime createdTime;
    /** 连续失败次数（周期任务重试语义用，成功后清零） */
    private Integer failureCount = 0;

    public ScheduledTask() {
    }

    /**
     * 创建一次性任务
     *
     * @param userId      用户标识
     * @param content     提醒内容
     * @param executeTime 执行时间
     */
    public ScheduledTask(String userId, String content, LocalDateTime executeTime) {
        this.userId = userId;
        this.content = content;
        this.triggerType = TRIGGER_TYPE_DELAY;
        this.executeTime = executeTime;
        this.repeatType = REPEAT_TYPE_NONE;
        this.repeatInterval = 1;
        this.nextExecuteTime = executeTime;
        this.status = STATUS_ACTIVE;
        this.createdTime = LocalDateTime.now();
    }

    // ==================== Getters & Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public LocalDateTime getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(LocalDateTime executeTime) {
        this.executeTime = executeTime;
    }

    public String getRepeatType() {
        return repeatType;
    }

    public void setRepeatType(String repeatType) {
        this.repeatType = repeatType;
    }

    public Integer getRepeatInterval() {
        return repeatInterval;
    }

    public void setRepeatInterval(Integer repeatInterval) {
        this.repeatInterval = repeatInterval;
    }

    public LocalDateTime getNextExecuteTime() {
        return nextExecuteTime;
    }

    public void setNextExecuteTime(LocalDateTime nextExecuteTime) {
        this.nextExecuteTime = nextExecuteTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTaskType() {
        return taskType != null ? taskType : TASK_TYPE_REMINDER;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public Integer getFailureCount() {
        return failureCount != null ? failureCount : 0;
    }

    public void setFailureCount(Integer failureCount) {
        this.failureCount = failureCount;
    }

    /** 连续失败次数 +1 */
    public int incrementFailureCount() {
        this.failureCount = getFailureCount() + 1;
        return this.failureCount;
    }

    /** 清零连续失败次数 */
    public void resetFailureCount() {
        this.failureCount = 0;
    }

    // ==================== 便捷判断 ====================

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 是否为一次性任务（NONE 或 ONCE） */
    public boolean isOnce() {
        return REPEAT_TYPE_NONE.equals(repeatType) || REPEAT_TYPE_ONCE.equals(repeatType);
    }

    /** 是否为周期任务（DAILY / WEEKLY / MONTHLY 等） */
    public boolean isRecurring() {
        return !isOnce();
    }

    /** 判断指定 repeatType 是否为一次性 */
    public static boolean isOnceType(String repeatType) {
        return repeatType == null || REPEAT_TYPE_NONE.equals(repeatType) || REPEAT_TYPE_ONCE.equals(repeatType);
    }

    /** 规范化 repeatType（将 ONCE 转为 NONE，其他不变） */
    public static String normalizeRepeatType(String repeatType) {
        if (repeatType == null) return REPEAT_TYPE_NONE;
        if (REPEAT_TYPE_ONCE.equals(repeatType)) return REPEAT_TYPE_NONE;
        return repeatType;
    }

    /** 判断是否为 Agent 任务 */
    public boolean isAgentTask() {
        return TASK_TYPE_AGENT.equals(taskType);
    }

    /** 判断是否为普通提醒任务 */
    public boolean isReminderTask() {
        return !isAgentTask();
    }

    /** 判断是否为暂停状态 */
    public boolean isPaused() {
        return STATUS_PAUSED.equals(status);
    }

    /** 判断是否可执行（ACTIVE 且未暂停） */
    public boolean isExecutable() {
        return isActive() && !isPaused();
    }

    // ==================== 序列化辅助 ====================

    public String getExecuteTimeAsString() {
        return executeTime != null ? executeTime.format(DTF) : null;
    }

    public void setExecuteTimeFromString(String str) {
        this.executeTime = (str != null && !str.isEmpty())
                ? LocalDateTime.parse(str, DTF)
                : null;
    }

    public String getNextExecuteTimeAsString() {
        return nextExecuteTime != null ? nextExecuteTime.format(DTF) : null;
    }

    public void setNextExecuteTimeFromString(String str) {
        this.nextExecuteTime = (str != null && !str.isEmpty())
                ? LocalDateTime.parse(str, DTF)
                : null;
    }

    public String getCreatedTimeAsString() {
        return createdTime != null ? createdTime.format(DTF) : null;
    }

    public void setCreatedTimeFromString(String str) {
        this.createdTime = (str != null && !str.isEmpty())
                ? LocalDateTime.parse(str, DTF)
                : null;
    }

    // ==================== 展示辅助 ====================

    public String getStatusDisplay() {
        return switch (status) {
            case STATUS_ACTIVE -> "待执行";
            case STATUS_RUNNING -> "执行中";
            case STATUS_DONE -> "已完成";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_FAILED -> "执行失败";
            case STATUS_PAUSED -> "已暂停";
            default -> status;
        };
    }

    public String getRepeatTypeDisplay() {
        if (repeatType == null) return "一次性";
        return switch (repeatType) {
            case REPEAT_TYPE_NONE, REPEAT_TYPE_ONCE -> "一次性";
            case REPEAT_TYPE_DAILY -> "每天";
            case REPEAT_TYPE_WEEKLY -> "每周";
            case REPEAT_TYPE_MONTHLY -> "每月";
            default -> repeatType;
        };
    }

    public String getTaskTypeDisplay() {
        return switch (getTaskType()) {
            case TASK_TYPE_REMINDER -> "提醒";
            case TASK_TYPE_AGENT -> "Agent 任务";
            default -> taskType;
        };
    }

    public String getExecuteTimeDisplay() {
        return executeTime != null
                ? executeTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                : "未设置";
    }
}