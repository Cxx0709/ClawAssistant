package com.youkeda.exercise.claw.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 动态技能加载器：支持运行时从多种来源加载技能。
 */
@Component
public class DynamicSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillLoader.class);

    private final SkillRegistry skillRegistry;
    private final Map<String, SkillProvider> providers = new LinkedHashMap<>();

    public DynamicSkillLoader(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;

        // 注册内置的提供者
        registerProvider("json", new JsonSkillProvider());
        registerProvider("classpath", new ClasspathSkillProvider());
    }

    /**
     * 注册技能提供者。
     */
    public void registerProvider(String name, SkillProvider provider) {
        providers.put(name, provider);
        log.info("Registered skill provider: {}", name);
    }

    /**
     * 从指定来源加载技能。
     * @param sourceType 来源类型（json, classpath, etc.）
     * @param source 来源标识（文件路径、URL等）
     * @return 加载的技能定义列表
     */
    public List<SkillDefinition> loadSkills(String sourceType, String source) {
        SkillProvider provider = providers.get(sourceType);
        if (provider == null) {
            log.warn("Unknown skill provider type: {}", sourceType);
            return Collections.emptyList();
        }

        try {
            List<SkillDefinition> skills = provider.load(source);
            log.info("Loaded {} skills from {} source: {}", skills.size(), sourceType, source);

            // 注册到 SkillRegistry
            for (SkillDefinition skill : skills) {
                skillRegistry.registerDynamic(skill);
            }

            return skills;
        } catch (Exception e) {
            log.error("Failed to load skills from {} source: {}", sourceType, source, e);
            return Collections.emptyList();
        }
    }

    /**
     * 刷新所有动态加载的技能。
     */
    public void refreshAll() {
        log.info("Refreshing all dynamic skills");
        skillRegistry.refreshDynamicSkills();
    }

    /**
     * 获取可用的技能提供者类型。
     */
    public Set<String> getAvailableProviderTypes() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /**
     * 技能提供者接口。
     */
    public interface SkillProvider {
        /**
         * 从指定来源加载技能定义。
         */
        List<SkillDefinition> load(String source) throws Exception;
    }

    /**
     * JSON文件技能提供者。
     */
    private static class JsonSkillProvider implements SkillProvider {
        private static final Logger log = LoggerFactory.getLogger(JsonSkillProvider.class);

        @Override
        public List<SkillDefinition> load(String source) throws Exception {
            // 解析JSON文件或URL
            // 格式示例：
            // [
            //   {
            //     "name": "customSkill",
            //     "description": "...",
            //     "priority": 5,
            //     "tags": ["custom"],
            //     "requiredTools": [...],
            //     "optionalTools": [...],
            //     "systemPromptResource": "prompts/skills/custom.txt"
            //   }
            // ]

            log.info("Loading skills from JSON: {}", source);
            // TODO: 实现JSON解析
            return Collections.emptyList();
        }
    }

    /**
     * Classpath技能提供者。
     */
    private static class ClasspathSkillProvider implements SkillProvider {
        private static final Logger log = LoggerFactory.getLogger(ClasspathSkillProvider.class);

        @Override
        public List<SkillDefinition> load(String source) throws Exception {
            // 从classpath加载技能定义文件
            log.info("Loading skills from classpath: {}", source);
            // TODO: 实现classpath资源加载
            return Collections.emptyList();
        }
    }
}
