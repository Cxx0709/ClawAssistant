package com.youkeda.exercise.claw.feature.goal;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Growth-goal Skill 的回复真实性校验守卫。
 *
 * <p>当模型声称「已创建/已推进/已取消」目标时，校验是否真正调用了 goal_manage 且返回 SUCCESS。
 * 未真正执行时注入修正提示，让 LLM 调用工具或改为追问。
 */
@Component
public class GoalReplyGuard implements SkillReplyGuard {

    private static final Pattern CLAIM_PATTERN = Pattern.compile(
            "(已创建|已推进|已更新|已取消|已完成|已帮你创建|已为你创建|已帮你推进|已为你推进|已帮你更新|已为你更新|已帮你取消|已为你取消|已帮你完成|已为你完成|目标创建完成|目标推进完成|目标更新完成|目标取消完成|目标已完成|目标已创建|目标已推进|目标已更新|目标已取消|goal_id[=：:])",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getSkillName() {
        return "growth-goal";
    }

    @Override
    public GuardResult validate(GuardContext context) {
        if (!CLAIM_PATTERN.matcher(context.reply()).find()) {
            return GuardResult.allow();
        }

        Set<String> executedCalls = context.executedCalls();
        Map<String, ResultStatus> toolStatuses = context.toolStatuses();

        boolean goalToolCalled = executedCalls != null
                && executedCalls.stream().anyMatch(call -> call.startsWith("goal_manage|"));
        boolean goalToolSucceeded = toolStatuses != null
                && toolStatuses.getOrDefault("goal_manage", ResultStatus.FAILED) == ResultStatus.SUCCESS;

        if (goalToolCalled && goalToolSucceeded) {
            return GuardResult.allow();
        }

        return GuardResult.reject(
                "你声称了目标操作已成功，但 goal_manage 工具未真正执行或未返回 SUCCESS。"
                + "请先调用 goal_manage 工具完成实际操作，再向用户确认结果。");
    }
}
