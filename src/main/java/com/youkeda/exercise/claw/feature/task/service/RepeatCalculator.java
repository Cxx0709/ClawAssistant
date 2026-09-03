package com.youkeda.exercise.claw.feature.task.service;

import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务重复周期计算器
 *
 * <p>策略模式封装，根据 {@link ScheduledTask#getRepeatType()} 选择对应的计算策略，
 * 避免 if-else 链。新增周期类型只需实现 {@link RepeatStrategy} 接口并注册即可。
 *
 * <p>当前支持：
 * <ul>
 *   <li>{@link ScheduledTask#REPEAT_TYPE_NONE} / {@link ScheduledTask#REPEAT_TYPE_ONCE} — 一次性，返回 null</li>
 *   <li>{@link ScheduledTask#REPEAT_TYPE_DAILY} — 每日，加 N 天</li>
 *   <li>{@link ScheduledTask#REPEAT_TYPE_WEEKLY} — 每周，加 7*N 天</li>
 *   <li>{@link ScheduledTask#REPEAT_TYPE_MONTHLY} — 每月，加 N 月</li>
 * </ul>
 */
@Component
public class RepeatCalculator {

    private static final Logger log = LoggerFactory.getLogger(RepeatCalculator.class);

    private final Map<String, RepeatStrategy> strategies = new ConcurrentHashMap<>();

    public RepeatCalculator() {
        // 注册内置策略
        register(ScheduledTask.REPEAT_TYPE_NONE, (task, lastExecute) -> null);
        register(ScheduledTask.REPEAT_TYPE_ONCE, (task, lastExecute) -> null);
        register(ScheduledTask.REPEAT_TYPE_DAILY, (task, lastExecute) -> {
            int interval = task.getRepeatInterval() != null ? task.getRepeatInterval() : 1;
            return lastExecute.plusDays(interval).truncatedTo(ChronoUnit.SECONDS);
        });
        register(ScheduledTask.REPEAT_TYPE_WEEKLY, (task, lastExecute) -> {
            int interval = task.getRepeatInterval() != null ? task.getRepeatInterval() : 1;
            return lastExecute.plusWeeks(interval).truncatedTo(ChronoUnit.SECONDS);
        });
        register(ScheduledTask.REPEAT_TYPE_MONTHLY, (task, lastExecute) -> {
            int interval = task.getRepeatInterval() != null ? task.getRepeatInterval() : 1;
            return lastExecute.plusMonths(interval).truncatedTo(ChronoUnit.SECONDS);
        });
        log.info("RepeatCalculator 初始化完成，已注册策略: NONE, ONCE, DAILY, WEEKLY, MONTHLY");
    }

    /**
     * 注册策略（支持扩展）
     *
     * @param repeatType 周期类型（如 MONTHLY、CUSTOM）
     * @param strategy   计算策略
     */
    public void register(String repeatType, RepeatStrategy strategy) {
        if (repeatType != null && strategy != null) {
            strategies.put(repeatType, strategy);
            log.debug("重复策略已注册: {}", repeatType);
        }
    }

    /**
     * 计算下次执行时间
     *
     * @param task         任务实体
     * @param lastExecute  本次实际执行时间
     * @return 下次执行时间，一次性任务返回 null
     */
    public LocalDateTime calculateNext(ScheduledTask task, LocalDateTime lastExecute) {
        if (task == null || lastExecute == null) return null;
        String repeatType = task.getRepeatType();
        if (repeatType == null || ScheduledTask.isOnceType(repeatType)) {
            return null;
        }
        RepeatStrategy strategy = strategies.get(repeatType);
        if (strategy == null) {
            log.warn("未找到重复策略 | repeatType={}", repeatType);
            return null;
        }
        LocalDateTime next = strategy.compute(task, lastExecute);
        log.debug("计算下次执行时间 | repeatType={} | lastExecute={} | next={}",
                repeatType, lastExecute, next);
        return next;
    }

    /**
     * 任务是否仍有后续执行（非一次性任务返回 true）
     */
    public boolean hasNext(ScheduledTask task) {
        if (task == null) return false;
        return task.isRecurring();
    }

    /**
     * 重复策略接口
     *
     * <p>实现此接口以支持新的周期类型。
     * 例如 MONTHLY 策略：{@code lastExecute.plusMonths(interval)}
     */
    @FunctionalInterface
    public interface RepeatStrategy {

        /**
         * 根据上次执行时间计算下次执行时间
         *
         * @param task        任务实体（可读取 repeatInterval 等配置）
         * @param lastExecute 本次实际执行时间
         * @return 下次执行时间，返回 null 表示不再执行
         */
        LocalDateTime compute(ScheduledTask task, LocalDateTime lastExecute);
    }
}