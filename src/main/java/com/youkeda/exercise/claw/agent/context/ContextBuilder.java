package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import com.youkeda.exercise.claw.agent.model.PlanState;

import java.util.List;

/**
 * 上下文组装器（ADR §4）。
 *
 * <p>替代 {@code MessageHistoryBuilder} 的组装职责：从多源
 * （当前 Turn / 最近 Turn / 目标状态 / Summary / LongTermMemory / Skill 知识）
 * 动态组装 LLM 上下文，按 token 预算 + 静态优先级裁剪，切在轮次边界。
 *
 * <p><b>只读源、不写存储</b>：持久化职责留在 {@code ContextStore}
 * （Phase 1B 的 beginTurn / appendToTurn）。
 *
 * <p>Phase 1A 冻结接口（ADR D2/D10）；实现（DefaultContextBuilder）在 Phase 1C。
 */
public interface ContextBuilder {

    /**
     * 组装一次 Agent Run 的 LLM 上下文。
     *
     * @param context 运行时执行上下文（含用户消息、userId、planState、skillSession）
     * @return 组装结果（messages 主产物 + 溯源元数据）
     */
    Result build(AgentContext context);

    /**
     * 判断是否为「继续生成」类延续请求（组装时据此过滤旧上限提示）。
     */
    boolean isContinuationRequest(String userMessage);

    /**
     * 组装结果（ADR §5 的 AgentContext 中间对象）。
     *
     * <p>因与既有运行时 {@code AgentContext} 重名，采用嵌套 Result 命名
     * （与 {@code ExecutionLoop.Result} 同惯例）。
     *
     * @param messages         主产物：组装好的 LLM 消息（含记忆注入的 system 消息）
     * @param metadata         溯源元数据（turnId + 各来源占用）
     * @param memories         注入的语义记忆（元数据）
     * @param plan             当前目标状态（元数据，供执行器使用，非消息）
     * @param budget           预算使用情况（Phase 1D 前为 unbounded）
     * @param coveredUntilTurn Summary 覆盖锚点（Phase 3 预留，0 = 未启用）
     */
    record Result(
            List<Message> messages,
            ContextMetadata metadata,
            List<MemoryItem> memories,
            PlanState plan,
            ContextBudget budget,
            int coveredUntilTurn) {

        public Result {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
