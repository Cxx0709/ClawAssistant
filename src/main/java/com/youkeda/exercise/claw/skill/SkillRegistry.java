package com.youkeda.exercise.claw.skill;

import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Map<String, SkillHealth> healthCache = new ConcurrentHashMap<>();
    private final SkillsProperties properties;
    private final ToolRegistry functionRegistry;
    private Set<String> registeredToolNames;

    public SkillRegistry(SkillsProperties properties,
                         ToolRegistry functionRegistry) {
        this.properties = properties;
        this.functionRegistry = functionRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
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
            }
            log.info("Skill [{}]: {}", name, health.status());
        });
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
}
