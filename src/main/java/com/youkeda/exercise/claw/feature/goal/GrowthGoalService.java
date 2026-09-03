package com.youkeda.exercise.claw.feature.goal;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 成长目标业务校验与用例编排。 */
@Service
public class GrowthGoalService {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_CRITERIA_LENGTH = 500;

    private final GrowthGoalRepository repository;

    public GrowthGoalService(GrowthGoalRepository repository) {
        this.repository = repository;
    }

    public GrowthGoal create(String userId, String title, String successCriteria, String deadline) {
        requireUserId(userId);
        String normalizedTitle = normalizeRequired(title, "title");
        if (normalizedTitle.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("目标标题不能超过120个字符");
        }

        String normalizedCriteria = successCriteria == null ? "" : successCriteria.trim();
        if (normalizedCriteria.length() > MAX_CRITERIA_LENGTH) {
            throw new IllegalArgumentException("成功标准不能超过500个字符");
        }

        String normalizedDeadline = normalizeDeadline(deadline);
        return repository.create(userId, normalizedTitle, normalizedCriteria, normalizedDeadline);
    }

    public List<GrowthGoal> list(String userId, GrowthGoal.Status status) {
        requireUserId(userId);
        return repository.findByUser(userId, status);
    }

    public GrowthGoal update(String userId, long goalId, String title, String successCriteria,
                              String deadline, Integer progress, String evidence) {
        requireUserId(userId);
        GrowthGoal updated = repository.update(userId, goalId, title, successCriteria,
                deadline, progress, evidence);
        if (updated == null) {
            throw new IllegalArgumentException("未找到可更新的目标（goalId=" + goalId + "），可能已完成或已取消");
        }
        return updated;
    }

    public GrowthGoal complete(String userId, long goalId) {
        requireUserId(userId);
        if (!repository.complete(userId, goalId)) {
            throw new IllegalArgumentException("未找到可完成的目标（goalId=" + goalId + "）");
        }
        return repository.findById(userId, goalId);
    }

    public boolean cancel(String userId, long goalId) {
        requireUserId(userId);
        return repository.cancel(userId, goalId);
    }

    private static String normalizeDeadline(String deadline) {
        if (deadline == null || deadline.isBlank()) return null;
        try {
            return LocalDate.parse(deadline.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("deadline 必须使用 yyyy-MM-dd 格式");
        }
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少用户ID");
        }
    }
}
