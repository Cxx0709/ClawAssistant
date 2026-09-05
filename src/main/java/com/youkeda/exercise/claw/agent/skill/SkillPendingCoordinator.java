package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SkillPendingCoordinator {

    /** 工具名 → 反应函数 注册表（批次 2：替代 if 链，Skill 触发语义靠注册表表达） */
    private final Map<String, ToolReaction> reactions = new LinkedHashMap<>();

    public SkillPendingCoordinator() {
        // 注册工具反应：新增工具状态机只需在这里加一行
    }

    public SkillSession afterToolExecution(SkillSession session, String toolName) {
        return afterToolExecution(session, toolName, null);
    }

    public SkillSession afterToolExecution(SkillSession session, String toolName, String result) {
        if (session == null) return null;
        ToolReaction reaction = reactions.get(toolName);
        return reaction != null ? reaction.apply(session, result) : session;
    }

    /** 工具执行后的反应函数：接收会话与执行结果，返回更新后的会话 */
    @FunctionalInterface
    public interface ToolReaction {
        SkillSession apply(SkillSession session, String result);
    }
}
