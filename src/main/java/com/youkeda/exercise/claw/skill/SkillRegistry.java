package com.youkeda.exercise.claw.skill;

import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Map<String, SkillDefinition> dynamicSkills = new ConcurrentHashMap<>();
    private final Map<String, SkillHealth> healthCache = new ConcurrentHashMap<>();
    /** 工具名 → 所属 skill 名。由 {@link #init()} 从 skills.yml 构建。 */
    private final Map<String, String> toolSkillMap = new ConcurrentHashMap<>();
    private final SkillsProperties properties;
    private final ToolRegistry functionRegistry;
    private Set<String> registeredToolNames;

    public SkillRegistry(SkillsProperties properties,
                         ToolRegistry functionRegistry) {
        this.properties = properties;
        this.functionRegistry = functionRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void init() {
        // Collect registered tool names at PostConstruct time, AFTER all
        // Tool implementations have registered themselves.
        this.registeredToolNames = functionRegistry.getAllDefinitions().stream()
                .map(td -> td.name())
                .collect(Collectors.toSet());

        properties.getSkills().forEach((name, configuredDef) -> {
            SkillDefinition def = withName(name, configuredDef);
            if (!def.enabled()) return;
            SkillHealth health = validate(name, def);
            healthCache.put(name, health);
            if (health.status() != SkillHealth.SkillStatus.UNAVAILABLE) {
                skills.put(name, def);
                // 构建 tool→skill 映射（ToolSkillResolver）
                for (String tool : def.allowedTools()) {
                    toolSkillMap.putIfAbsent(tool, name);
                }
            }
            log.info("Skill [{}]: {}", name, health.status());
        });
        log.info("ToolSkillResolver 映射已构建 | toolCount={}", toolSkillMap.size());
    }

    /**
     * 根据工具名查找其所属的 skill 名（ToolSkillResolver）。
     * 工具执行完成后，用此映射自动锁定 activeSkill。
     */
    public String resolveSkillForTool(String toolName) {
        return toolSkillMap.get(toolName);
    }

    private SkillDefinition withName(String name, SkillDefinition def) {
        return new SkillDefinition(
                name,
                def.description(),
                def.priority(),
                def.tags(),
                def.requiredTools(),
                def.optionalTools(),
                def.systemPromptResource(),
                def.triggerPolicyName(),
                def.knowledge(),
                def.execution(),
                def.enabled()
        );
    }

    private SkillHealth validate(String name, SkillDefinition def) {
        Set<String> missingRequired = new LinkedHashSet<>();
        Set<String> missingOptional = new LinkedHashSet<>();

        if (def.requiredTools() != null) {
            for (String tool : def.requiredTools()) {
                if (!registeredToolNames.contains(tool)) missingRequired.add(tool);
            }
        }
        if (def.optionalTools() != null) {
            for (String tool : def.optionalTools()) {
                if (!registeredToolNames.contains(tool)) missingOptional.add(tool);
            }
        }

        SkillHealth.SkillStatus status;
        if (!missingRequired.isEmpty()) {
            status = SkillHealth.SkillStatus.UNAVAILABLE;
            log.error("Skill [{}] is UNAVAILABLE: missing required tools: {}", name, missingRequired);
        } else if (!missingOptional.isEmpty()) {
            status = SkillHealth.SkillStatus.DEGRADED;
            log.warn("Skill [{}] is DEGRADED: missing optional tools: {}", name, missingOptional);
        } else {
            status = SkillHealth.SkillStatus.HEALTHY;
        }

        return new SkillHealth(name, status, missingOptional, missingRequired);
    }

    public Optional<SkillDefinition> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<SkillDefinition> getAll() {
        return skills.values();
    }

    public List<SkillDefinition> findByTag(String tag) {
        return skills.values().stream()
                .filter(s -> s.tags() != null && s.tags().contains(tag))
                .toList();
    }

    public SkillHealth getHealth(String name) {
        return healthCache.get(name);
    }

    public Map<String, SkillHealth> getAllHealth() {
        return Collections.unmodifiableMap(healthCache);
    }

    /**
     * 动态注册技能。
     */
    public void registerDynamic(SkillDefinition skill) {
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            log.warn("Cannot register null or empty-named skill");
            return;
        }

        String name = skill.name();
        SkillHealth health = validate(name, skill);
        healthCache.put(name, health);

        if (health.status() != SkillHealth.SkillStatus.UNAVAILABLE) {
            dynamicSkills.put(name, skill);
            skills.put(name, skill);
            log.info("Dynamically registered skill [{}]: {}", name, health.status());
        } else {
            log.warn("Dynamic skill [{}] is UNAVAILABLE: {}", name, health.missingRequiredTools());
        }
    }

    /**
     * 刷新所有动态技能。
     */
    public void refreshDynamicSkills() {
        log.info("Refreshing {} dynamic skills", dynamicSkills.size());
        for (Map.Entry<String, SkillDefinition> entry : dynamicSkills.entrySet()) {
            String name = entry.getKey();
            SkillDefinition skill = entry.getValue();

            SkillHealth health = validate(name, skill);
            healthCache.put(name, health);

            if (health.status() != SkillHealth.SkillStatus.UNAVAILABLE) {
                skills.put(name, skill);
            } else {
                skills.remove(name);
                log.warn("Dynamic skill [{}] became UNAVAILABLE after refresh", name);
            }
        }
    }

    /**
     * 移除动态技能。
     */
    public void unregisterDynamic(String name) {
        dynamicSkills.remove(name);
        skills.remove(name);
        healthCache.remove(name);
        log.info("Unregistered dynamic skill: {}", name);
    }

    /**
     * 获取所有动态技能名称。
     */
    public Set<String> getDynamicSkillNames() {
        return Collections.unmodifiableSet(dynamicSkills.keySet());
    }

    /**
     * 检查技能是否为动态注册。
     */
    public boolean isDynamic(String name) {
        return dynamicSkills.containsKey(name);
    }
}
