package com.youkeda.exercise.claw.agent.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class SkillLlmRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillLlmRouter.class);

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;
    /** 有界线程池：队列满 + 线程达上限时由调用线程兜底执行（CallerRunsPolicy），避免无界队列 OOM */
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10),
            r -> {
                Thread t = new Thread(r, "skill-llm-router");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final Duration timeout = Duration.ofSeconds(5);

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public SkillLlmRouter(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public SkillRoutingResult route(String message, String userId, SkillRegistry registry) {
        if (message == null || message.isBlank()) {
            return SkillRoutingResult.fallback();
        }

        List<SkillDefinition> allSkills = registry.getAll().stream()
                .filter(s -> !"common".equals(s.name()))
                .toList();

        if (allSkills.isEmpty()) return SkillRoutingResult.fallback();

        String skillDesc = allSkills.stream()
                .map(s -> "- " + s.name() + ": " + (s.description() != null ? s.description() : ""))
                .collect(Collectors.joining("\n"));

        String prompt = "你是一个意图分类器。分析用户消息的意图，输出JSON格式。\n"
                + "可用 Skill:\n" + skillDesc + "\n"
                + "如果都不匹配，输出 common。\n"
                + "输出格式: {\"primaryIntent\": \"skill名\", \"confidence\": 0.0~1.0, \"reason\": \"...\", \"secondaryIntents\": [\"skill名2\", ...]}\n"
                + "如果消息涉及多个技能意图，填入 secondaryIntents。\n"
                + "只输出JSON，不要其他内容。";

        Future<SkillRoutingResult> future = null;
        try {
            future = executor.submit(() -> {
                String result = llmClient.chatWithSystemPrompt(prompt, message,
                        java.util.Collections.emptyList());
                return parseLlmResponse(result);
            });

            SkillRoutingResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("LLM Router: message=[{}] -> {}", message, result);
            return result;

        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("LLM Router timeout after {}ms for message: {}", timeout.toMillis(), message);
            return SkillRoutingResult.fallback();
        } catch (RejectedExecutionException e) {
            log.warn("LLM Router executor rejected task, fallback | message={}", message);
            return SkillRoutingResult.fallback();
        } catch (Exception e) {
            log.error("LLM Router failed for message: {}", message, e);
            return SkillRoutingResult.fallback();
        }
    }

    private SkillRoutingResult parseLlmResponse(String response) {
        try {
            String json = extractJson(response);
            if (json == null) return SkillRoutingResult.fallback();

            JsonNode node = objectMapper.readTree(json);
            String intent = node.has("primaryIntent") ? node.get("primaryIntent").asText() : "common";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.0;

            if ("common".equals(intent) || confidence < 0.4) {
                return SkillRoutingResult.fallback();
            }

            // 解析辅助技能（可选）
            Set<String> secondaryIntents = new java.util.HashSet<>();
            if (node.has("secondaryIntents") && node.get("secondaryIntents").isArray()) {
                for (JsonNode secondaryNode : node.get("secondaryIntents")) {
                    String secondaryIntent = secondaryNode.asText();
                    if (secondaryIntent != null && !secondaryIntent.isBlank() && !"common".equals(secondaryIntent)) {
                        secondaryIntents.add(secondaryIntent);
                    }
                }
            }

            String reason = node.has("reason") ? node.get("reason").asText() : "LLM Router match";

            // 多技能激活
            if (!secondaryIntents.isEmpty()) {
                return SkillRoutingResult.multi(intent, secondaryIntents, Set.of(), confidence, reason);
            }

            return SkillRoutingResult.of(intent, Set.of(),
                    SkillRoutingResult.SkillRoutingAction.ACTIVATE,
                    confidence, reason);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM Router response: {}", response, e);
            return SkillRoutingResult.fallback();
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }
}
