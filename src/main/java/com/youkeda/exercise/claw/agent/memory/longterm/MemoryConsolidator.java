package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Decides whether a candidate memory should be added, ignored, updated, or merged. */
@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final String PROMPT = """
            比较已有记忆与新记忆，选择一种操作：
            - ADD：描述不同属性或可以独立共存，应新增
            - DUPLICATE：含义相同，没有新增信息
            - UPDATE：新信息纠正、否定或替代旧信息
            - MERGE：两者描述同一主题且可以合并保留

            UPDATE 的 content 使用新事实；MERGE 的 content 输出简洁、无矛盾的合并结果。
            不得添加输入中不存在的信息。只输出 JSON：
            {"action":"ADD|DUPLICATE|UPDATE|MERGE","content":"最终记忆内容"}
            """;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public MemoryConsolidator(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public MemoryMergeDecision decide(MemoryItem existing, MemoryItem incoming) {
        if (existing.content().strip().equalsIgnoreCase(incoming.content().strip())) {
            return new MemoryMergeDecision(MemoryMergeAction.DUPLICATE, existing.content());
        }

        try {
            String input = "已有主题键：" + existing.topicKey()
                    + "\n新主题键：" + incoming.topicKey()
                    + "\n已有记忆：" + existing.content()
                    + "\n新记忆：" + incoming.content();
            String output = llmClient.chatWithSystemPrompt(PROMPT, input);
            JsonNode node = objectMapper.readTree(stripCodeFence(output));
            MemoryMergeAction action = MemoryMergeAction.valueOf(
                    node.path("action").asText("").toUpperCase());
            String content = node.path("content").asText("").strip();
            if ((action == MemoryMergeAction.UPDATE || action == MemoryMergeAction.MERGE)
                    && content.isBlank()) {
                throw new IllegalArgumentException("consolidated content is blank");
            }
            return new MemoryMergeDecision(action, content);
        } catch (Exception e) {
            log.warn("记忆合并判断失败，使用保守策略 | existing={} | incoming={}",
                    existing.content(), incoming.content());
            return fallback(existing, incoming);
        }
    }

    private MemoryMergeDecision fallback(MemoryItem existing, MemoryItem incoming) {
        boolean sameTopic = !incoming.topicKey().isBlank()
                && incoming.topicKey().equals(existing.topicKey());
        if (sameTopic || isExplicitCorrection(incoming.content())) {
            return new MemoryMergeDecision(MemoryMergeAction.UPDATE, incoming.content());
        }
        return new MemoryMergeDecision(MemoryMergeAction.ADD, incoming.content());
    }

    private boolean isExplicitCorrection(String content) {
        if (content == null) return false;
        String normalized = content.toLowerCase();
        return normalized.contains("不再")
                || normalized.contains("现在不")
                || normalized.contains("已经不")
                || normalized.contains("不能")
                || normalized.contains("不喜欢")
                || normalized.contains("不要")
                || normalized.contains("改为")
                || normalized.contains("改成")
                || normalized.contains("取消")
                || normalized.contains("停止")
                || normalized.contains("no longer")
                || normalized.contains("cannot")
                || normalized.contains("don't")
                || normalized.contains("do not")
                || normalized.contains("instead");
    }

    private String stripCodeFence(String output) {
        if (output == null) return "{}";
        String json = output.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "")
                    .replaceAll("\\s*```$", "");
        }
        return json;
    }
}
