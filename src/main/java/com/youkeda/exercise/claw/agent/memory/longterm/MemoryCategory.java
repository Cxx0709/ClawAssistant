package com.youkeda.exercise.claw.agent.memory.longterm;

/**
 * 长期记忆分类
 */
public enum MemoryCategory {

    /** 用户偏好：饮食口味、出行方式、预算习惯、风格喜好 */
    PREFERENCE,

    /** 用户规则：明确的约束、禁忌、固定要求 */
    RULE,

    /** 长期事实：姓名、生日、所在城市、职业、家人信息 */
    FACT,

    /** 长期目标：正在策划的旅行、求职、学习计划 */
    GOAL,

    /** 历史经验：过去的好坏体验、推荐和避坑 */
    EXPERIENCE
}
