package com.youkeda.exercise.claw.agent.runtime;

import com.youkeda.exercise.claw.ai.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 *
 * <p>管理所有可供 LLM 调用的 {@link Tool}。
 * 所有工具实现必须通过 {@link #register(Tool)} 在此注册才能被 Agent Runtime 发现。
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap}。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("工具已注册: name={}, description={}", tool.getName(), tool.getDescription());
    }

    /**
     * 根据名称查找工具
     *
     * @param name 工具名
     * @return 匹配的工具，未找到返回 null
     */
    public Tool find(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册的工具定义（用于发给 LLM 的 tools 参数）
     *
     * @return 工具定义列表
     */
    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(Tool::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户消息允许使用的工具定义。
     *
     * @param context 本轮工具执行上下文
     * @return 通过各工具可用性校验的定义列表
     */
    public List<ToolDefinition> getAvailableDefinitions(ToolExecutionContext context) {
        return tools.values().stream()
                .filter(tool -> tool.isAvailable(context))
                .map(Tool::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * 按白名单 + 可用性过滤获取工具定义
     * <p>计算公式：{@code allowedNames ∩ registered ∩ isAvailable(context)}
     *
     * @param allowedNames 允许的工具名集合
     * @param context      执行上下文
     * @return 过滤后的工具定义列表
     */
    public List<ToolDefinition> getAvailableDefinitions(
            Set<String> allowedNames,
            ToolExecutionContext context) {
        if (allowedNames == null || allowedNames.isEmpty()) return List.of();
        return tools.values().stream()
                .filter(t -> allowedNames.contains(t.getName()))
                .filter(t -> t.isAvailable(context))
                .map(Tool::toDefinition)
                .collect(Collectors.toList());
    }
}
