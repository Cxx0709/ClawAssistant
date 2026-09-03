package com.youkeda.exercise.claw.feature.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.agent.runtime.LoopSuspensionPolicy;
import com.youkeda.exercise.claw.ai.llm.LLMResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 信息猎手静默策略。
 *
 * <p>当 {@code information_scout} 工具返回 {@code status=started} 时，视为
 * 后台任务已受理，本轮无需回复用户（由 {@code ExecutionLoop} 静默返回）。
 * 逻辑从原 {@code ToolExecutor.isStartedInformationScout} 迁出，使内核不感知业务工具名。
 *
 * <p>位于 feature/scout：内核只依赖 {@link LoopSuspensionPolicy} 接口，
 * 此实现经 Spring 注入，不在 tool 包（tool 包类须实现 Tool 接口）。
 *
 * <p>批次 3 已删除 {@code ScoutTool}（LLM 白名单不含该工具，实际不可达）。
 * 本策略为防御性钩子：若未来重引入 {@code information_scout} 工具，此判定仍生效。
 */
@Component
public class InformationScoutSuspensionPolicy implements LoopSuspensionPolicy {

    private final ObjectMapper objectMapper;

    public InformationScoutSuspensionPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean shouldSuspend(List<LLMResponse.ToolCall> toolCalls, List<String> toolResults) {
        if (toolCalls == null || toolResults == null) return false;
        for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
            if (!"information_scout".equals(toolCalls.get(i).name())) continue;
            try {
                JsonNode result = objectMapper.readTree(toolResults.get(i));
                if ("started".equalsIgnoreCase(result.path("status").asText())) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非 JSON 结果不能视为已受理后台任务。
            }
        }
        return false;
    }
}
