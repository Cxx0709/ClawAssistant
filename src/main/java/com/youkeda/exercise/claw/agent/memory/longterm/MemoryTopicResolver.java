package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Resolves a stable topic key for manually saved memories. */
@Component
public class MemoryTopicResolver {

    private static final Logger log = LoggerFactory.getLogger(MemoryTopicResolver.class);
    private static final String PROMPT = """
            为一条用户记忆生成稳定的主题键。主题键用于判断两条信息是否描述同一个可更新属性。

            规则：
            1. 使用小写英文和点号，格式类似 diet.spicy、travel.budget、profile.birthday
            2. 同一属性的肯定、否定和新旧取值必须使用相同键
            3. 不同的可并存偏好应使用不同键，例如 cuisine.sichuan 与 cuisine.hunan
            4. confidence 表示主题识别置信度，范围 0.0 到 1.0

            只输出 JSON：{"topicKey":"diet.spicy","confidence":0.95}
            """;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public MemoryTopicResolver(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public TopicResolution resolve(MemoryCategory category, String content) {
        try {
            String output = llmClient.chatWithSystemPrompt(
                    PROMPT, "分类：" + category + "\n记忆：" + content);
            JsonNode node = objectMapper.readTree(stripCodeFence(output));
            String topicKey = node.path("topicKey").asText("").toLowerCase(Locale.ROOT);
            float confidence = (float) node.path("confidence").asDouble(0.5d);
            if (!isValidTopicKey(topicKey) || !Float.isFinite(confidence)
                    || confidence < 0f || confidence > 1f) {
                throw new IllegalArgumentException("invalid topic resolution");
            }
            return new TopicResolution(topicKey, confidence);
        } catch (Exception e) {
            // An empty key keeps the semantic-consolidation fallback available.
            // A content hash would make every correction look like a different topic.
            log.warn("记忆主题识别失败，使用语义降级 | category={} | content={}",
                    category, content);
            return new TopicResolution("", 0.3f);
        }
    }

    static boolean isValidTopicKey(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{2,63}");
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

    public record TopicResolution(String topicKey, float confidence) {
    }
}
