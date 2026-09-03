package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.skill.SkillsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 通用能力注册中心
 *
 * <p>存放跨 Skill 的通用能力工具（common capability tools）。
 * 这些工具不属于任何单一 Skill，而是对所有活跃 Skill 可用。
 *
 * <p>与 globalTools 的区别：
 * <ul>
 *   <li>globalTools：系统级工具（如 memory_manage），始终可用</li>
 *   <li>commonCapabilityTools：通用能力工具（如 web_search、file_generate），
 *       跨 Skill 共享，但不属于系统级</li>
 *   <li>activeSkill.allowedTools()：各 Skill 专属工具</li>
 * </ul>
 *
 * <p>配置来源：skills.yml 中的 {@code claw.common-capability-tools}
 */
@Component
public class CommonCapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommonCapabilityRegistry.class);

    private final Set<String> commonCapabilityTools;

    public CommonCapabilityRegistry(SkillsProperties skillsProperties) {
        Set<String> tools = skillsProperties.getCommonCapabilityTools();
        this.commonCapabilityTools = tools != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(tools))
                : Collections.emptySet();
        log.info("CommonCapabilityRegistry 已初始化: {} tools", this.commonCapabilityTools.size());
    }

    /**
     * 获取所有通用能力工具名（不可修改集合）
     */
    public Set<String> getTools() {
        return commonCapabilityTools;
    }

    /**
     * 检查指定工具是否为通用能力工具
     */
    public boolean contains(String toolName) {
        return commonCapabilityTools.contains(toolName);
    }

    /**
     * 获取通用能力工具数量
     */
    public int size() {
        return commonCapabilityTools.size();
    }
}
