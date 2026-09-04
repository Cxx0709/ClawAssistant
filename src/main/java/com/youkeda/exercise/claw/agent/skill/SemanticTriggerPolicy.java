package com.youkeda.exercise.claw.agent.skill;

import com.youkeda.exercise.claw.ai.llm.EmbeddingClient;
import com.youkeda.exercise.claw.skill.SkillDefinition;
import com.youkeda.exercise.claw.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于语义嵌入的触发策略。
 *
 * <p>通过计算用户消息与技能代表性示例的向量相似度来识别意图，
 * 泛化能力比关键词更强（如「下雨天怎么去机场」可同时命中 weather + transport）。
 */
@Component("semanticTriggerPolicy")
public class SemanticTriggerPolicy implements SkillTriggerPolicy {

    private static final Logger log = LoggerFactory.getLogger(SemanticTriggerPolicy.class);

    /** 语义匹配阈值：高于此值才认为匹配 */
    private static final double MATCH_THRESHOLD = 0.65;

    /** 高置信度阈值 */
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.80;

    private final EmbeddingClient embeddingClient;
    private final SkillRegistry skillRegistry;
    private final TriggerProperties triggerProperties;

    /** 缓存：skillName -> 其代表性示例的平均嵌入向量 */
    private final Map<String, float[]> skillEmbeddingCache = new ConcurrentHashMap<>();

    /** 每个技能的代表性示例（关键词 + 扩展表达） */
    private final Map<String, List<String>> skillRepresentativeExamples = new ConcurrentHashMap<>();

    public SemanticTriggerPolicy(EmbeddingClient embeddingClient,
                                  SkillRegistry skillRegistry,
                                  TriggerProperties triggerProperties) {
        this.embeddingClient = embeddingClient;
        this.skillRegistry = skillRegistry;
        this.triggerProperties = triggerProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1) // SkillRegistry registers configured skills at order 0.
    public void initializeAfterSkillsReady() {
        initializeRepresentativeExamples();
        log.info("SemanticTriggerPolicy initialized with {} skills", skillEmbeddingCache.size());
    }

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> currentSession) {
        if (message == null || message.isBlank()) {
            return SkillTriggerMatch.noMatch();
        }

        try {
            // 计算消息的嵌入向量
            float[] messageEmbedding = embeddingClient.embed(message);
            if (isEmptyEmbedding(messageEmbedding)) {
                return SkillTriggerMatch.noMatch();
            }

            List<SkillMatchResult> matches = new ArrayList<>();

            // 与所有技能的代表性示例比较
            for (Map.Entry<String, float[]> entry : skillEmbeddingCache.entrySet()) {
                String skillName = entry.getKey();
                float[] skillEmbedding = entry.getValue();

                float similarity = EmbeddingClient.cosineSimilarity(messageEmbedding, skillEmbedding);

                if (similarity >= MATCH_THRESHOLD) {
                    matches.add(new SkillMatchResult(skillName, similarity));
                }
            }

            if (matches.isEmpty()) {
                return SkillTriggerMatch.noMatch();
            }

            // 按相似度排序
            matches.sort((a, b) -> Float.compare(b.similarity(), a.similarity()));

            SkillMatchResult topMatch = matches.get(0);
            double confidence = topMatch.similarity();

            // 单技能匹配
            if (matches.size() == 1) {
                return new SkillTriggerMatch(
                        true,
                        confidence,
                        "semantic match: " + topMatch.skillName() + " (similarity=" + String.format("%.2f", confidence) + ")",
                        false
                );
            }

            // 多技能匹配，返回最高置信度的，但记录冲突
            if (matches.size() > 1 && matches.get(1).similarity() >= HIGH_CONFIDENCE_THRESHOLD) {
                log.debug("Multiple high-confidence semantic matches: {}", matches);
            }

            return new SkillTriggerMatch(
                    true,
                    confidence,
                    "semantic match: " + topMatch.skillName() + " (similarity=" + String.format("%.2f", confidence) + ")",
                    false
            );

        } catch (Exception e) {
            log.warn("Semantic matching failed: {}", e.getMessage());
            return SkillTriggerMatch.noMatch();
        }
    }

    /**
     * 初始化技能的代表性示例。
     * 使用技能的关键词 + 扩展表达方式，计算平均嵌入向量。
     */
    private void initializeRepresentativeExamples() {
        Map<String, List<String>> triggerMap = triggerProperties.getTriggers();

        for (SkillDefinition skill : skillRegistry.getAll()) {
            String skillName = skill.name();
            if ("common".equals(skillName)) continue;

            List<String> examples = new ArrayList<>();

            // 使用配置的关键词
            List<String> keywords = triggerMap.get(skillName);
            if (keywords != null) {
                examples.addAll(keywords);
            }

            // 添加技能描述作为补充
            if (skill.description() != null && !skill.description().isBlank()) {
                examples.add(skill.description());
            }

            // 扩展一些常见的表达方式
            examples.addAll(generateExpandedExamples(skillName, keywords));

            if (!examples.isEmpty()) {
                skillRepresentativeExamples.put(skillName, examples);
                // 计算平均嵌入向量（缓存）
                computeAndCacheSkillEmbedding(skillName, examples);
            }
        }
    }

    /**
     * 为技能生成扩展示例。
     */
    private List<String> generateExpandedExamples(String skillName, List<String> keywords) {
        List<String> expanded = new ArrayList<>();

        if (keywords == null || keywords.isEmpty()) {
            return expanded;
        }

        // 基于关键词生成一些变体
        switch (skillName) {
            case "travel" -> {
                expanded.add("帮我规划一次旅行");
                expanded.add("我想出去玩几天");
                expanded.add("安排一下周末出游");
            }
            case "weather" -> {
                expanded.add("明天会下雨吗");
                expanded.add("今天适合出门吗");
                expanded.add("这周末天气怎么样");
            }
            case "transport" -> {
                expanded.add("怎么去这个地方");
                expanded.add("坐什么车过去");
                expanded.add("打车还是坐地铁");
            }
            case "campus" -> {
                expanded.add("我的课表是什么");
                expanded.add("下周有考试吗");
                expanded.add("帮我查一下课程安排");
            }
            case "anime" -> {
                expanded.add("有什么新番推荐");
                expanded.add("这部番好看吗");
                expanded.add("追一下最新一集");
            }
            case "image" -> {
                expanded.add("帮我生成一张图片");
                expanded.add("画一幅画");
                expanded.add("生成一张卡通风格的图");
            }
            case "growth-goal" -> {
                expanded.add("设定一个学习目标");
                expanded.add("查看我的目标进度");
                expanded.add("更新一下完成情况");
            }
        }

        return expanded;
    }

    /**
     * 计算并缓存技能的平均嵌入向量。
     */
    private void computeAndCacheSkillEmbedding(String skillName, List<String> examples) {
        try {
            List<float[]> embeddings = embeddingClient.embedBatch(examples);

            if (embeddings.isEmpty()) {
                return;
            }

            // 计算平均向量
            int dimensions = embeddingClient.getDimensions();
            float[] avgEmbedding = new float[dimensions];

            for (float[] embedding : embeddings) {
                for (int i = 0; i < dimensions; i++) {
                    avgEmbedding[i] += embedding[i];
                }
            }

            for (int i = 0; i < dimensions; i++) {
                avgEmbedding[i] /= embeddings.size();
            }

            skillEmbeddingCache.put(skillName, avgEmbedding);
            log.debug("Cached embedding for skill [{}]: {} examples", skillName, examples.size());

        } catch (Exception e) {
            log.warn("Failed to compute embedding for skill [{}]: {}", skillName, e.getMessage());
        }
    }

    private boolean isEmptyEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return true;
        }
        for (float val : embedding) {
            if (val != 0.0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 刷新指定技能的嵌入缓存。
     */
    public void refreshSkillEmbedding(String skillName) {
        List<String> examples = skillRepresentativeExamples.get(skillName);
        if (examples != null) {
            computeAndCacheSkillEmbedding(skillName, examples);
        }
    }

    /**
     * 刷新所有技能的嵌入缓存。
     */
    public void refreshAllEmbeddings() {
        for (Map.Entry<String, List<String>> entry : skillRepresentativeExamples.entrySet()) {
            computeAndCacheSkillEmbedding(entry.getKey(), entry.getValue());
        }
    }

    public int getExampleCount(String skillName) {
        return skillRepresentativeExamples.getOrDefault(skillName, List.of()).size();
    }

    public boolean hasSkillEmbedding(String skillName) {
        return !isEmptyEmbedding(skillEmbeddingCache.get(skillName));
    }

    /** Embed a diagnostic query once and compare each cached skill separately. */
    public Map<String, Double> similarities(String message) {
        Map<String, float[]> available = new TreeMap<>();
        skillEmbeddingCache.forEach((name, vector) -> {
            if (!isEmptyEmbedding(vector)) available.put(name, vector);
        });
        if (available.isEmpty()) throw new IllegalStateException("技能向量缓存尚未就绪，请检查嵌入服务和初始化日志");
        float[] query = embeddingClient.embed(message);
        if (isEmptyEmbedding(query)) throw new IllegalStateException("无法生成查询向量，请检查嵌入服务配置或连接");
        Map<String, Double> scores = new LinkedHashMap<>();
        available.forEach((name, vector) -> {
            if (query.length == vector.length) {
                double score = EmbeddingClient.cosineSimilarity(query, vector);
                if (Double.isFinite(score)) scores.put(name, score);
            }
        });
        if (scores.isEmpty()) throw new IllegalStateException("查询向量和技能向量维度不一致，请检查嵌入模型配置");
        return scores;
    }

    private record SkillMatchResult(String skillName, float similarity) {}
}
