package com.youkeda.exercise.claw.feature.task;

import com.youkeda.exercise.claw.agent.runtime.ScheduleIntent;
import com.youkeda.exercise.claw.agent.runtime.ScheduleIntentResolver;
import com.youkeda.exercise.claw.agent.runtime.ScheduleReplyInspector;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardContext;
import com.youkeda.exercise.claw.agent.runtime.SkillReplyGuard.GuardResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 定时提醒创建防幻觉 guard。
 *
 * <p>原逻辑内联在 {@code ExecutionLoop} 分支 2，批次 2 外移到 feature/task：
 * <ul>
 *   <li>仅用户确为「创建定时提醒」意图时拦截</li>
 *   <li>层1：模型声称已创建/设置，但本轮循环未实际调用 {@code create_schedule_task} → 幻觉，强制重试</li>
 *   <li>层2：模型既未声称完成、也未向用户澄清（卡壳/敷衍）→ 提示补做</li>
 *   <li>反问「几点提醒你呢？」属澄清，放行，避免「帮我设置提醒」死循环</li>
 * </ul>
 */
@Component
public class ScheduleReplyGuard implements SkillReplyGuard {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReplyGuard.class);

    /** 工具调用签名前缀（与 ToolExecutor 的 {@code toolName|arguments} 格式一致） */
    private static final String CREATE_SCHEDULE_SIGNATURE_PREFIX = "create_schedule_task|";

    /** 横切：定时提醒意图不绑定特定 skill，由 {@link ScheduleIntentResolver} 自行判断 */
    @Override
    public String getSkillName() {
        return null;
    }

    @Override
    public GuardResult validate(GuardContext context) {
        String userMessage = context.userMessage();
        String reply = context.reply();
        Set<String> executedCalls = context.executedCalls();
        if (userMessage == null || reply == null || executedCalls == null) {
            return GuardResult.allow();
        }
        boolean createIntent =
                ScheduleIntentResolver.resolve(userMessage) == ScheduleIntent.CREATE;
        boolean toolCalled = wasScheduleTaskCalled(executedCalls);
        if (!createIntent || toolCalled) {
            return GuardResult.allow();
        }

        boolean claimsDone = ScheduleReplyInspector.claimsCreation(reply);
        boolean asksClarification = ScheduleReplyInspector.asksForClarification(reply);
        if (claimsDone || !asksClarification) {
            String hint = claimsDone
                    ? "注意：你刚才的回复声称已创建/设置定时提醒，但并未实际调用"
                      + " create_schedule_task 工具。请先调用 create_schedule_task 完成创建，"
                      + "创建成功后再回复用户。不要重复调用已经执行过的工具。"
                    : "注意：用户要求创建定时提醒，但你尚未调用 create_schedule_task 工具。"
                      + "请调用 create_schedule_task 完成创建；若必要信息不足，"
                      + "请先向用户提问澄清，不要直接结束。";
            log.warn("LLM 幻觉检测：{}，注入提示重试",
                    claimsDone ? "声称已创建但未调用 create_schedule_task"
                               : "创建意图未执行且未向用户澄清");
            return GuardResult.reject(hint);
        }
        return GuardResult.allow();
    }

    /** 检查 executedCalls 中是否已包含 create_schedule_task 的调用记录 */
    private static boolean wasScheduleTaskCalled(Set<String> executedCalls) {
        for (String sig : executedCalls) {
            if (sig.startsWith(CREATE_SCHEDULE_SIGNATURE_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
