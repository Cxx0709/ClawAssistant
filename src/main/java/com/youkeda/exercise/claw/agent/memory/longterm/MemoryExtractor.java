package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆提取器
 *
 * 使用 LLM 从对话中自动提取值得长期保存的用户信息。
 * 单次调用同时完成：信息提取 + 分类 + 重要性评分。
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final String EXTRACT_PROMPT = """
            你是记忆提取器。分析以下对话，提取值得长期记住的用户信息。

            【提取规则】
            1. 只提取用户主动透露的、对未来对话有持续价值的信息
            2. 不提取：通用知识、一次性查询结果、工具返回的临时数据、闲聊寒暄、天气/地图/搜索等实时查询
            3. 每条记忆用简洁的自然语言描述（一句话，不超过50字）
            4. topicKey 使用稳定的小写英文主题键；同一属性的新旧取值必须使用相同键
            5. confidence 表示用户是否明确表达该信息，范围 0.0~1.0
            6. 【去重】如果用户原话中包含"记住"、"记一下"、"帮我记"、"保存"等明确要求保存记忆的表述，说明该内容已通过记忆工具手动保存，绝对不要重复提取本条内容

            【分类定义】
            - PREFERENCE：用户偏好（饮食口味、出行方式、预算习惯、风格喜好）
            - RULE：用户规则（明确的约束、禁忌、固定要求，如"不要推荐贵的"）
            - FACT：长期事实（姓名、生日、所在城市、职业、家人信息）
            - GOAL：长期目标（正在策划的旅行、求职、学习计划）
            - EXPERIENCE：历史经验（过去的好坏体验、推荐和避坑）

            【重要性评分 0.0 ~ 1.0】
            - 0.8~1.0：核心信息，几乎每次对话都可能用到（用户身份、核心偏好）
            - 0.5~0.7：重要信息，在相关场景下会用到（具体目标、规则）
            - 0.2~0.4：补充信息，偶尔有用（一次性的体验反馈）
            - 0.0~0.1：几乎无用，不要保存

            【输出格式】
            只输出 JSON 数组，不要输出任何其他内容：
            [{"topicKey":"diet.spicy","content":"记忆内容","category":"PREFERENCE|RULE|FACT|GOAL|EXPERIENCE","importance":0.8,"confidence":0.95}]

            如果没有值得记忆的信息，输出空数组：[]
            """;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public MemoryExtractor(LLMClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从一轮对话中提取值得长期记忆的信息
     *
     * @param userMessage   用户消息
     * @param assistantReply 助手回复
     * @return 提取出的记忆列表（可能为空）
     */
    public List<MemoryItem> extract(String userMessage, String assistantReply) {
        // Only user-authored text may become a user fact. Assistant output can contain
        // suggestions or hallucinations and must never be treated as evidence.
        String conversation = "只分析下面的用户原话，不要补充或推断未明确表达的信息：\n用户原话："
                + userMessage;

        String result = llmClient.chatWithSystemPrompt(EXTRACT_PROMPT, conversation);
        if (result == null || result.isBlank()) {
            log.debug("记忆提取返回空");
            return List.of();
        }

        List<MemoryItem> memories = parseExtractionResult(userMessage, result);
        if (!memories.isEmpty()) {
            log.info("记忆提取成功 | count={}", memories.size());
        }
        return memories;
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     */
    private List<MemoryItem> parseExtractionResult(
            String evidence, String llmOutput) {
        List<MemoryItem> items = new ArrayList<>();
        try {
            // 清理可能的 markdown 代码块包裹
            String json = llmOutput.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                log.warn("记忆提取输出不是数组: {}", llmOutput);
                return items;
            }

            for (JsonNode node : array) {
                try {
                    String content = node.path("content").asText("");
                    String categoryStr = node.path("category").asText("PREFERENCE");
                    float importance = (float) node.path("importance").asDouble(0.5);
                    float confidence = (float) node.path("confidence").asDouble(0.5);
                    String topicKey = node.path("topicKey").asText("").toLowerCase();

                    if (content.isBlank()) continue;
                    if (!Float.isFinite(importance) || importance < 0f || importance > 1f) {
                        log.warn("记忆重要性评分无效，跳过 | importance={} | content={}",
                                importance, content);
                        continue;
                    }
                    if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
                        confidence = 0.3f;
                    }
                    if (!MemoryTopicResolver.isValidTopicKey(topicKey)) {
                        log.warn("记忆主题键无效，将使用保守合并 | topicKey={} | content={}",
                                topicKey, content);
                        topicKey = "";
                    }

                    MemoryCategory category;
                    try {
                        category = MemoryCategory.valueOf(categoryStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("记忆分类无效，跳过 | category={} | content={}",
                                categoryStr, content);
                        continue;
                    }

                    items.add(MemoryItem.ofAuto(
                            category, topicKey, content, evidence,
                            importance, confidence));
                } catch (Exception e) {
                    log.warn("单条记忆解析失败 | node={}", node, e);
                }
            }
        } catch (Exception e) {
            log.warn("记忆提取结果 JSON 解析失败 | output={}", llmOutput, e);
        }
        return items;
    }
}
