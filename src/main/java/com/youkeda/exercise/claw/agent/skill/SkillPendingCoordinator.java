package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SkillPendingCoordinator {

    public static final String START_INFORMATION_SCOUT = "START_INFORMATION_SCOUT";

    /** didi_ride 估价完成后的待确认标记：期间任何回复都保持 transport skill */
    public static final String RIDE_ESTIMATE_CONFIRM = "RIDE_ESTIMATE_CONFIRM";

    /** 工具名 → 反应函数 注册表（批次 2：替代 if 链，Skill 触发语义靠注册表表达） */
    private final Map<String, ToolReaction> reactions = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;

    public SkillPendingCoordinator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 注册工具反应：新增工具状态机只需在这里加一行
        // information_scout 为防御性钩子（ScoutTool 已删，若未来重引入工具则此反应仍生效）
        reactions.put("information_scout", (session, result) -> session.clearPendingAction());
        reactions.put("didi_ride", this::handleDidiRide);
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

    /**
     * didi_ride 状态流转（P0-8：结构化解析，替代字符串包含匹配）：
     * <ul>
     *   <li>status=estimate_completed → 设置待确认标记，后续追问校区/车型期间保持 transport</li>
     *   <li>status=order_created / cancelled → 订单已闭环，清除待确认标记</li>
     * </ul>
     */
    private SkillSession handleDidiRide(SkillSession session, String result) {
        if (result == null) return session;
        String status = extractStatus(result);
        if ("estimate_completed".equals(status)) {
            return session.withPendingAction(RIDE_ESTIMATE_CONFIRM, null);
        }
        if ("order_created".equals(status) || "cancelled".equals(status)) {
            return session.clearPendingAction();
        }
        return session;
    }

    /** 从工具返回 JSON 中提取 status 字段；非 JSON 或缺失返回 null */
    private String extractStatus(String result) {
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.path("status").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
