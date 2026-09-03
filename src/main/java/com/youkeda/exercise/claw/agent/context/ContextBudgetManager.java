package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算管理器（ADR §4.3/§6）。
 *
 * <p>按静态优先级 + token 预算裁剪 Turn 列表。核心不变式：
 * <ul>
 *   <li><b>切在轮次边界</b>——绝不切开一个 Turn（Turn 是原子单位）</li>
 *   <li><b>最新 Turn 必含</b>——即使最新 Turn 单独就超预算，也强制保留（用户当前意图）</li>
 *   <li>预算 ≤ 0 视为 unbounded，不裁剪</li>
 * </ul>
 *
 * <p>独立组件，{@code ContextBuilder} 依赖它，不内嵌。
 */
public class ContextBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetManager.class);

    private final TokenEstimator estimator;

    public ContextBudgetManager(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * 在预算内保留最近 Turn（最新在前输入）。
     *
     * <p>从旧到新裁剪：保留最新的 K 个 Turn，使累计估算 ≤ maxTokens；
     * 无论如何保留最新 1 个。返回仍为最新在前（保留的最新 K 个）。
     *
     * @param turnsNewestFirst 最近 Turn 列表（最新在前，来自 {@code getTurns}）
     * @param maxTokens        token 预算；≤0 视为 unbounded 不裁剪
     */
    public List<ConversationTurn> trimToBudget(List<ConversationTurn> turnsNewestFirst, int maxTokens) {
        if (maxTokens <= 0 || turnsNewestFirst == null || turnsNewestFirst.isEmpty()) {
            return turnsNewestFirst == null ? List.of() : turnsNewestFirst;
        }

        long total = 0;
        int kept = 0;
        for (ConversationTurn turn : turnsNewestFirst) {
            int tokens = estimator.estimateTurn(turn);
            // 已有至少一个（最新）Turn 后，再加会超预算 → 裁掉本 Turn 及所有更旧
            if (kept > 0 && total + tokens > maxTokens) {
                break;
            }
            total += tokens;
            kept++;
        }

        if (kept >= turnsNewestFirst.size()) {
            return turnsNewestFirst;
        }
        // 最新在前，保留前 kept 个；kept 至少为 1（最新必含）
        kept = Math.max(1, kept);
        log.debug("预算裁剪 | maxTokens={} | 保留 {} 个 Turn | 估算 {} tokens",
                maxTokens, kept, total);
        return new ArrayList<>(turnsNewestFirst.subList(0, kept));
    }
}
