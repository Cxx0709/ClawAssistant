package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.agent.model.ResultStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 旅游规划交付不变量守卫。
 *
 * <p>只做「交付质量」确定性校验，不强制流程：
 * <ul>
 *   <li>声称完成行程，但 travel_collect 本轮未调用或未收集齐（PARTIAL=NEED_MORE_INFORMATION）→ 拦截</li>
 *   <li>回复含预算结论（总费用/人均/预算内/超预算），但本轮未调 travel_calculate_cost → 拦截</li>
 * </ul>
 *
 * <p>与 cxx-tools 版本的区别：不依赖 SkillSession 的 travel 常量（Session/Plan 边界），
 * 只靠 toolStatuses；不含「天数不得超过用户要求」规则——该规则属策略，由 travel.txt prompt 引导，
 * 避免 guard 用正则猜用户天数造成误伤（用户说「3 天 2 晚」时 2 晚不是第 3 天）。
 */
@Component
public class TravelReplyGuard implements SkillReplyGuard {

    private static final Pattern COMPLETED_PLAN = Pattern.compile(
            "行程总览|行程安排|行程已生成|规划好了|已为你规划好|完整行程|Day\\s*1|第一天");

    private static final Pattern BUDGET_SUMMARY = Pattern.compile(
            "总费用|总价|人均费用|人均价|预算内|超预算|合计.*元|共.*元");

    @Override
    public String getSkillName() {
        return "travel";
    }

    @Override
    public GuardResult validate(GuardContext context) {
        if (context.reply() == null || context.reply().isBlank()) {
            return GuardResult.allow();
        }
        Map<String, ResultStatus> statuses = context.toolStatuses();
        boolean collected = statuses.getOrDefault("travel_collect", ResultStatus.FAILED) == ResultStatus.SUCCESS;

        // 不变量 1：旅行请求 + 声称完成行程 + travel_collect 未收集齐 → 拦截
        boolean claimsCompleted = COMPLETED_PLAN.matcher(context.reply()).find();
        if (claimsCompleted && !collected) {
            return GuardResult.reject(
                    "当前是旅游规划请求，但你尚未调用 travel_collect 收集齐需求，"
                            + "或该工具仍返回缺失字段。请先调用 travel_collect 记录已提供信息，"
                            + "根据返回的 missing_fields 追问缺失项；需求未齐全前不得声称已完成行程。");
        }

        // 不变量 2：预算结论必须本轮有 travel_calculate_cost 计算凭据
        ResultStatus costStatus = statuses.getOrDefault(
                "travel_calculate_cost", ResultStatus.FAILED);
        boolean costCalculated = costStatus == ResultStatus.SUCCESS
                || costStatus == ResultStatus.PARTIAL;
        if (BUDGET_SUMMARY.matcher(context.reply()).find() && !costCalculated) {
            return GuardResult.reject(
                    "你的回复包含总费用/人均费用/预算结论，但本轮尚未调用 travel_calculate_cost。"
                            + "请先调用 travel_calculate_cost 核算后再给出金额结论。");
        }

        return GuardResult.allow();
    }
}
