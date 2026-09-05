package com.youkeda.exercise.claw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 通用工具调用安全检查（与业务无关）。
 *
 * <p>负责 CanExecute 阶段的检查——调用是否合法、参数是否合理。
 * 不涉及业务领域判断（阶段、顺序等）。
 *
 * <p>Phase 4：支持 Tool 风险分级。
 * <ul>
 *   <li>NONE — 无风险，直接放行</li>
 *   <li>LOW — 低风险，自动执行 + 记录日志</li>
 *   <li>MEDIUM — 中风险，记录警告日志 + 可配置确认</li>
 *   <li>HIGH — 高风险，强制返回 BLOCKED_CONFIRM_REQUIRED</li>
 * </ul>
 */
@Component
public class SafetyPolicy {

    private static final Logger log = LoggerFactory.getLogger(SafetyPolicy.class);

    /**
     * 不可被 LLM 调用的工具黑名单。
     */
    private static final Set<String> BLACKLISTED_TOOLS = Set.of();

    /**
     * 高风险工具：执行前必须返回 BLOCKED_CONFIRM_REQUIRED。
     * 涉及数据删除、外部下单、消息发送、支付等不可逆操作。
     */
    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "file_delete",
            "file_update"
    );

    /**
     * 中风险工具：记录警告日志，后续可扩展确认机制。
     * 涉及数据写入但非破坏性操作。
     */
    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "file_save",
            "file_generate",
            "image_generate",
            "text_to_speech",
            "course_schedule",
            "exam_schedule",
            "anime_subscribe",
            "goal_manage"
    );

    /**
     * 低风险工具：自动执行 + 记录 info 日志。
     * 涉及查询类或可逆操作。
     */
    private static final Set<String> LOW_RISK_TOOLS = Set.of(
            "web_search",
            "map_search_place",
            "map_route_planning",
            "map_distance_calculate",
            "travel_collect",
            "travel_save_options",
            "travel_calculate_cost",
            "place_image_search",
            "transport_recommend",
            "anime_recommend",
            "exam_reminder_setup"
    );

    /** 是否要求高风险工具确认（可通过配置关闭，默认开启） */
    private final boolean highRiskConfirmationEnabled;

    public SafetyPolicy(
            @Value("${agent.safety.high-risk-confirmation-enabled:true}")
            boolean highRiskConfirmationEnabled) {
        this.highRiskConfirmationEnabled = highRiskConfirmationEnabled;
        log.info("SafetyPolicy 初始化 | highRiskConfirmationEnabled={} | "
                + "HIGH={} | MEDIUM={} | LOW={} | BLACKLISTED={}",
                highRiskConfirmationEnabled,
                HIGH_RISK_TOOLS.size(), MEDIUM_RISK_TOOLS.size(),
                LOW_RISK_TOOLS.size(), BLACKLISTED_TOOLS.size());
    }

    /** For tests without Spring */
    SafetyPolicy() {
        this.highRiskConfirmationEnabled = true;
    }

    /**
     * @return null 表示允许；非空字符串表示阻止原因。
     *         返回 {@code BLOCKED_CONFIRM_REQUIRED} 表示需要用户确认。
     */
    public String canExecute(String toolName, String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return "工具名称为空";
        }
        if (BLACKLISTED_TOOLS.contains(toolName)) {
            return "工具 " + toolName + " 被安全策略禁止调用";
        }

        // Phase 4: 风险分级检查
        if (highRiskConfirmationEnabled && HIGH_RISK_TOOLS.contains(toolName)) {
            log.warn("高风险工具需要确认 | tool={} | args={}",
                    toolName, truncateArgs(argumentsJson));
            return "BLOCKED_CONFIRM_REQUIRED";
        }

        if (MEDIUM_RISK_TOOLS.contains(toolName)) {
            log.warn("中风险工具执行 | tool={} | args={}",
                    toolName, truncateArgs(argumentsJson));
        }

        if (LOW_RISK_TOOLS.contains(toolName)) {
            log.debug("低风险工具执行 | tool={}", toolName);
        }

        return null;
    }

    /**
     * 查询工具风险等级（供外部使用）
     */
    public ToolRiskLevel getRiskLevel(String toolName) {
        if (HIGH_RISK_TOOLS.contains(toolName)) return ToolRiskLevel.HIGH;
        if (MEDIUM_RISK_TOOLS.contains(toolName)) return ToolRiskLevel.MEDIUM;
        if (LOW_RISK_TOOLS.contains(toolName)) return ToolRiskLevel.LOW;
        return ToolRiskLevel.NONE;
    }

    /**
     * 工具风险等级。
     */
    public enum ToolRiskLevel {
        /** 无需确认，默认放行 */
        NONE,
        /** 自动执行，记录 info 日志 */
        LOW,
        /** 自动执行，记录 warning 日志，后续可扩展确认 */
        MEDIUM,
        /** 强制确认：返回 BLOCKED_CONFIRM_REQUIRED */
        HIGH
    }

    private static String truncateArgs(String args, int maxLen) {
        if (args == null) return "{}";
        return args.length() <= maxLen ? args : args.substring(0, maxLen) + "...";
    }

    private static String truncateArgs(String args) {
        return truncateArgs(args, 120);
    }
}
