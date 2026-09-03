package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按当前 Skill 分发文本回复结束条件校验。 */
@Component
public class SkillReplyGuardRegistry {

    private final Map<String, SkillReplyGuard> guards = new LinkedHashMap<>();
    private final List<SkillReplyGuard> globalGuards = new java.util.ArrayList<>();

    public SkillReplyGuardRegistry(List<SkillReplyGuard> guards) {
        for (SkillReplyGuard guard : guards) {
            String skillName = guard.getSkillName();
            if (skillName == null || skillName.isBlank()) {
                globalGuards.add(guard);
                continue;
            }
            SkillReplyGuard previous = this.guards.putIfAbsent(skillName, guard);
            if (previous != null) {
                throw new IllegalStateException(
                        "重复的 SkillReplyGuard: " + skillName);
            }
        }
    }

    public GuardResult validate(
            String activeSkillName,
            String userMessage,
            String reply,
            SkillSession session,
            Set<String> executedCalls,
            Map<String, ResultStatus> toolStatuses) {
        if (toolStatuses == null) {
            toolStatuses = Map.of();
        }
        if (executedCalls == null) {
            executedCalls = Set.of();
        }
        SkillReplyGuard skillGuard = activeSkillName == null ? null : guards.get(activeSkillName);
        if (skillGuard != null) {
            GuardResult r = skillGuard.validate(new GuardContext(
                    userMessage, reply, session, Set.copyOf(executedCalls),
                    Map.copyOf(toolStatuses)));
            if (!r.allowed()) return r;
        }
        for (SkillReplyGuard global : globalGuards) {
            GuardResult r = global.validate(new GuardContext(
                    userMessage, reply, session, Set.copyOf(executedCalls),
                    Map.copyOf(toolStatuses)));
            if (!r.allowed()) return r;
        }
        return GuardResult.allow();
    }
}
