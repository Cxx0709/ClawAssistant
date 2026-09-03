package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.skill.DynamicSkillLoader;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillHealth;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 动态技能管理 API。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillManagementController {

    private static final Logger log = LoggerFactory.getLogger(SkillManagementController.class);

    private final DynamicSkillLoader dynamicSkillLoader;
    private final SkillRegistry skillRegistry;

    public SkillManagementController(DynamicSkillLoader dynamicSkillLoader,
                                      SkillRegistry skillRegistry) {
        this.dynamicSkillLoader = dynamicSkillLoader;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 列出所有可用的技能。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (SkillDefinition skill : skillRegistry.getAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", skill.name());
            item.put("description", skill.description());
            item.put("priority", skill.priority());
            item.put("tags", skill.tags());
            item.put("enabled", skill.enabled());
            item.put("isDynamic", skillRegistry.isDynamic(skill.name()));

            SkillHealth health = skillRegistry.getHealth(skill.name());
            if (health != null) {
                item.put("healthStatus", health.status().name());
                item.put("missingRequired", health.missingRequiredTools());
                item.put("missingOptional", health.missingOptionalTools());
            }

            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取单个技能详情。
     */
    @GetMapping("/{skillName}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String skillName) {
        Optional<SkillDefinition> skillOpt = skillRegistry.find(skillName);

        if (skillOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SkillDefinition skill = skillOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", skill.name());
        result.put("description", skill.description());
        result.put("priority", skill.priority());
        result.put("tags", skill.tags());
        result.put("requiredTools", skill.requiredTools());
        result.put("optionalTools", skill.optionalTools());
        result.put("systemPromptResource", skill.systemPromptResource());
        result.put("triggerPolicyName", skill.triggerPolicyName());
        result.put("enabled", skill.enabled());
        result.put("isDynamic", skillRegistry.isDynamic(skill.name()));

        SkillHealth health = skillRegistry.getHealth(skill.name());
        if (health != null) {
            result.put("healthStatus", health.status().name());
            result.put("missingRequired", health.missingRequiredTools());
            result.put("missingOptional", health.missingOptionalTools());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 从 JSON 加载动态技能。
     */
    @PostMapping("/load/json")
    public ResponseEntity<Map<String, Object>> loadFromJson(@RequestBody String jsonSource) {
        log.info("Loading skills from JSON: {}", jsonSource);

        List<SkillDefinition> loaded = dynamicSkillLoader.loadSkills("json", jsonSource);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loadedCount", loaded.size());
        result.put("skillNames", loaded.stream().map(SkillDefinition::name).toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 从 classpath 加载动态技能。
     */
    @PostMapping("/load/classpath")
    public ResponseEntity<Map<String, Object>> loadFromClasspath(@RequestParam String resourcePath) {
        log.info("Loading skills from classpath: {}", resourcePath);

        List<SkillDefinition> loaded = dynamicSkillLoader.loadSkills("classpath", resourcePath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loadedCount", loaded.size());
        result.put("skillNames", loaded.stream().map(SkillDefinition::name).toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 刷新所有动态技能。
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshDynamicSkills() {
        log.info("Refreshing all dynamic skills");

        Set<String> before = skillRegistry.getDynamicSkillNames();
        dynamicSkillLoader.refreshAll();
        Set<String> after = skillRegistry.getDynamicSkillNames();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dynamicSkillNames", after);
        result.put("totalDynamicSkills", after.size());

        return ResponseEntity.ok(result);
    }

    /**
     * 移除动态技能。
     */
    @DeleteMapping("/{skillName}")
    public ResponseEntity<Map<String, Object>> removeDynamicSkill(@PathVariable String skillName) {
        if (!skillRegistry.isDynamic(skillName)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Skill is not dynamic or does not exist");
            return ResponseEntity.badRequest().body(error);
        }

        skillRegistry.unregisterDynamic(skillName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removedSkill", skillName);
        result.put("remainingDynamicSkills", skillRegistry.getDynamicSkillNames());

        return ResponseEntity.ok(result);
    }

    /**
     * 获取可用的技能提供者类型。
     */
    @GetMapping("/providers")
    public ResponseEntity<Set<String>> getProviderTypes() {
        return ResponseEntity.ok(dynamicSkillLoader.getAvailableProviderTypes());
    }
}
