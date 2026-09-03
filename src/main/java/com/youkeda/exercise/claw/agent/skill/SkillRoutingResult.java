package com.youkeda.exercise.claw.agent.skill;

import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * 技能路由结果。
 *
 * <p>支持多技能并行激活：primarySkill 处理主意图，secondarySkills 可同时激活处理辅助意图。
 * 例如：「下雨天怎么去机场」可返回 primary=transport, secondary={weather}。
 */
public record SkillRoutingResult(
        String primarySkill,
        Set<String> secondarySkills,  // 辅助技能（可同时激活）
        Set<String> supportingSkills,
        @Nullable SkillRoutingAction action,
        double confidence,
        String reason
) {
    public enum SkillRoutingAction {
        CONTINUE,
        SWITCH,
        ACTIVATE,
        MULTI_ACTIVATE,  // 新增：多技能并行激活
        DEACTIVATE,
        NONE
    }

    /**
     * 创建单技能路由结果（向后兼容）。
     */
    public static SkillRoutingResult of(String skill, Set<String> supporting,
                                         SkillRoutingAction action, double confidence, String reason) {
        return new SkillRoutingResult(skill, Set.of(), supporting, action, confidence, reason);
    }

    /**
     * 创建多技能并行路由结果。
     */
    public static SkillRoutingResult multi(String primary, Set<String> secondary,
                                            Set<String> supporting, double confidence, String reason) {
        return new SkillRoutingResult(primary, secondary != null ? secondary : Set.of(),
                supporting != null ? supporting : Set.of(),
                SkillRoutingAction.MULTI_ACTIVATE, confidence, reason);
    }

    /**
     * 获取所有激活的技能（主 + 辅）。
     */
    public Set<String> allActiveSkills() {
        Set<String> all = new java.util.LinkedHashSet<>();
        all.add(primarySkill);
        all.addAll(secondarySkills);
        return all;
    }

    /**
     * 是否包含多个激活技能。
     */
    public boolean isMultiSkill() {
        return !secondarySkills.isEmpty();
    }

    /**
     * 获取指定技能的置信度（主技能使用原始置信度，辅助技能使用较低置信度）。
     */
    public double confidenceFor(String skillName) {
        if (primarySkill.equals(skillName)) {
            return confidence;
        }
        // 辅助技能置信度略低
        return confidence * 0.85;
    }

    public static SkillRoutingResult fallback() {
        return new SkillRoutingResult("common", Set.of(), Set.of(), SkillRoutingAction.NONE, 0.0, "fallback to common");
    }
}
