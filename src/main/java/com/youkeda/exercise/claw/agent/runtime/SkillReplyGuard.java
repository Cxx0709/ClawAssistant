package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import com.youkeda.exercise.claw.agent.skill.SkillSession;

import java.util.Map;
import java.util.Set;

/**
 * Skill 文本回复结束条件的确定性校验扩展点。
 *
 * <p>在 LLM 准备以文本结束（分支 2）或合成最终回复前调用：
 * 校验「声称完成的副作用是否有工具调用凭据」「数字结论是否有计算凭据」等交付不变量。
 * 不强制流程线性执行——流程由 prompt 引导，guard 只兜底「交付质量」。
 *
 * <p>{@link #getSkillName()} 返回 null 表示对所有 skill 生效（横切 guard）。
 */
public interface SkillReplyGuard {

    String getSkillName();

    GuardResult validate(GuardContext context);

    record GuardContext(
            String userMessage,
            String reply,
            SkillSession session,
            Set<String> executedCalls,
            Map<String, ResultStatus> toolStatuses
    ) {}

    record GuardResult(boolean allowed, String correction) {
        public static GuardResult allow() {
            return new GuardResult(true, null);
        }

        public static GuardResult reject(String correction) {
            return new GuardResult(false, correction);
        }
    }
}
