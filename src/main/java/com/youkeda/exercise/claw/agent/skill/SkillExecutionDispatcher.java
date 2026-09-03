package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillExecutionResult;
import com.youkeda.exercise.claw.skill.SkillExecutionMode;
import com.youkeda.exercise.claw.skill.SkillExecutionRequest;
import com.youkeda.exercise.claw.skill.SkillsProperties;
import com.youkeda.exercise.claw.agent.runtime.SkillExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SkillExecutionDispatcher {

    private final Map<String, SkillExecutor> executors;
    private final SkillsProperties properties;

    public SkillExecutionDispatcher(List<SkillExecutor> executors,
                                    SkillsProperties properties) {
        this.executors = new LinkedHashMap<>();
        for (SkillExecutor executor : executors) {
            SkillExecutor previous = this.executors.putIfAbsent(
                    executor.getName(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "重复的 SkillExecutor: " + executor.getName());
            }
        }
        this.properties = properties;
    }

    public SkillExecutionResult dispatch(SkillDefinition skill,
                                         String currentMessage,
                                         SkillSession session) {
        if (skill == null || skill.execution() == null
                || skill.execution().mode() == null
                || skill.execution().mode() == SkillExecutionMode.INLINE) {
            return SkillExecutionResult.notHandled(session);
        }

        String executorName = skill.execution().executorName();
        SkillExecutor executor = executors.get(executorName);
        if (executor == null) {
            return SkillExecutionResult.failed(
                    "当前功能暂时不可用，请稍后重试。", session);
        }
        String workflowName = properties.getSkillWorkflowBindings()
                .get(skill.name());
        return executor.execute(new SkillExecutionRequest(
                skill, currentMessage, session, workflowName));
    }
}
