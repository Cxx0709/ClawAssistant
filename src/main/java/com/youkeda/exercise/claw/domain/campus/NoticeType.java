package com.youkeda.exercise.claw.domain.campus;

public enum NoticeType {
    // === 考试类（已有）===
    FINAL_EXAM,       // 期末考试
    CET,              // 四六级
    RETAKE,           // 补考/重修
    MIDTERM,          // 期中考试
    COMPUTER_LEVEL,   // 计算机等级考试
    PUTONGHUA,        // 普通话测试
    OTHER_EXAM,       // 其他考试

    // === 比赛类（Phase 1 新增）===
    CHALLENGE_CUP,        // 挑战杯
    INTERNET_PLUS,        // 互联网+
    INNOVATION_EXPO,      // 大创
    ACADEMIC_COMPETITION, // 学科竞赛
    SKILL_COMPETITION,    // 技能大赛
    OTHER_COMPETITION,    // 其他比赛

    // === 活动类（Phase 2）===
    CAMPUS_EVENT,
    LECTURE,
    CLUB_ACTIVITY,
    OTHER_ACTIVITY,

    // === 就业类（Phase 2）===
    CAREER_FAIR,
    ELITE_TALK,
    INTERN_RECRUIT,
    JOB_GUIDANCE,
    OTHER_JOB,

    // === 通用 ===
    NON_EXAM,         // 非通知
    UNKNOWN           // 无法判断
}
