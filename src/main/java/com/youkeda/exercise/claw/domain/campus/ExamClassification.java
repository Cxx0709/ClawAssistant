package com.youkeda.exercise.claw.domain.campus;

public record ExamClassification(
    NoticeType type,
    double confidence,
    String reason,
    String scoreSource
) {}
