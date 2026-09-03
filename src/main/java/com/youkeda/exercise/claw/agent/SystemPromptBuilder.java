package com.youkeda.exercise.claw.agent;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 动态 system prompt 构建器。
 *
 * <p>从 {@code ReActAgentExecutor} 拆出：基础 prompt + 当前时间 + Active Skill 上下文
 * + RAG 知识库上下文。非 Spring bean，由 {@code ReActAgentExecutor} 构造时用已有依赖创建。
 */
public class SystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptBuilder.class);

    private final LLMClient llmClient;
    private final SkillKnowledgeService skillKnowledgeService;

    public SystemPromptBuilder(LLMClient llmClient, SkillKnowledgeService skillKnowledgeService) {
        this.llmClient = llmClient;
        this.skillKnowledgeService = skillKnowledgeService;
    }

    /**
     * 构建动态 system prompt：基础 prompt + Active Skill 上下文。
     */
    public String build(AgentContext context, SkillDefinition activeSkill) {
        StringBuilder sb = new StringBuilder();
        sb.append(llmClient.getSystemPrompt()).append("\n\n");

        // 注入当前系统时间，供 LLM 判断「今晚/明天/已过去」等时间相关表述
        sb.append("当前系统时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");

        if (activeSkill != null && activeSkill.systemPromptResource() != null) {
            String skillPrompt = loadSkillPrompt(activeSkill.systemPromptResource());
            if (skillPrompt != null) {
                sb.append("--- 当前上下文 ---\n\n");
                sb.append("[SKILL_CONTEXT]\n");
                sb.append(skillPrompt).append("\n");
                sb.append("[/SKILL_CONTEXT]\n\n");
            }
        }
        // RAG knowledge context
        if (skillKnowledgeService != null && activeSkill != null
                && activeSkill.knowledge() != null && activeSkill.knowledge().enabled()) {
            try {
                String knowledge = skillKnowledgeService.recall(context.getMessage(), activeSkill.name());
                if (knowledge != null && !knowledge.isEmpty()) {
                    sb.append(knowledge).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to recall skill knowledge for: {}", activeSkill.name(), e);
            }
        }

        return sb.toString();
    }

    /**
     * 从 classpath 加载 Skill 的 system prompt 资源文件。
     */
    private String loadSkillPrompt(String resourcePath) {
        try {
            return new String(
                new org.springframework.core.io.ClassPathResource(resourcePath)
                    .getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            log.warn("Failed to load skill prompt: {}", resourcePath, e);
            return null;
        }
    }
}
