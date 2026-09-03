package com.youkeda.exercise.claw.feature.scout.processor;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient;
import com.youkeda.exercise.claw.feature.scout.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 信息处理器
 *
 * 清洗 → 去重 → LLM 摘要 → Embedding
 */
@Service
public class InformationProcessor {

    private static final Logger log = LoggerFactory.getLogger(InformationProcessor.class);

    private static final String SUMMARY_PROMPT = """
            请为以下信息生成一句话中文摘要（不超过50字），保留原始事实和信息所属领域。
            不要改变信息所属领域，不要强行改写成开发者或创业主题。

            标题：{title}
            类别：{category}
            内容：{content}

            只返回摘要文本，不要加任何前缀。
            """;

    private final EmbeddingClient embeddingClient;
    private final LLMClient llmClient;

    public InformationProcessor(EmbeddingClient embeddingClient, LLMClient llmClient) {
        this.embeddingClient = embeddingClient;
        this.llmClient = llmClient;
    }

    /** 向量去重相似度阈值 */
    private static final float DEDUP_SIMILARITY_THRESHOLD = 0.90f;

    /**
     * 处理采集到的原始信息
     */
    public List<InformationItem> process(List<InformationItem> items) {
        if (items == null || items.isEmpty()) return List.of();

        // 1. 清洗
        List<InformationItem> cleaned = items.stream()
                .map(this::clean)
                .filter(item -> item.getTitle() != null && !item.getTitle().isBlank())
                .toList();

        // 2. URL 去重
        List<InformationItem> urlDeduped = deduplicateByUrl(cleaned);

        // 3. LLM 摘要（限制数量，避免太多 LLM 调用）
        List<InformationItem> summarized = summarize(urlDeduped);

        // 4. Embedding（向量去重需要先有向量）
        List<InformationItem> embedded = embed(summarized);

        // 5. 向量相似度去重
        List<InformationItem> vectorDeduped = deduplicateByVector(embedded);

        log.info("信息处理完成 | input={} | cleaned={} | urlDeduped={} | summarized={} | embedded={} | vectorDeduped={}",
                items.size(), cleaned.size(), urlDeduped.size(), summarized.size(),
                embedded.size(), vectorDeduped.size());

        return vectorDeduped;
    }

    /**
     * 清洗单条信息
     */
    private InformationItem clean(InformationItem item) {
        // 截断过长内容
        if (item.getContent() != null && item.getContent().length() > 500) {
            item.setContent(item.getContent().substring(0, 500) + "...");
        }

        // 去除标题中的特殊字符
        if (item.getTitle() != null) {
            item.setTitle(item.getTitle().replaceAll("[\\r\\n]+", " ").trim());
        }

        return item;
    }

    /**
     * 按 URL 去重
     */
    private List<InformationItem> deduplicateByUrl(List<InformationItem> items) {
        Set<String> seen = new HashSet<>();
        List<InformationItem> result = new ArrayList<>();

        for (InformationItem item : items) {
            String key = item.getSource();
            if (key == null || key.isBlank()) {
                key = item.getTitle(); // 无 URL 时按标题去重
            }

            if (key != null && !seen.contains(key)) {
                seen.add(key);
                result.add(item);
            }
        }

        return result;
    }

    /**
     * 按向量相似度去重
     *
     * 如果两条信息的向量余弦相似度 > 阈值，保留先出现的那条
     */
    private List<InformationItem> deduplicateByVector(List<InformationItem> items) {
        if (items.isEmpty()) return items;

        List<InformationItem> result = new ArrayList<>();
        List<float[]> keptVectors = new ArrayList<>();

        for (InformationItem item : items) {
            if (item.getVector() == null) {
                // 无向量的条目直接保留
                result.add(item);
                continue;
            }

            // 与已保留的向量逐一比较
            boolean isDuplicate = false;
            for (float[] kept : keptVectors) {
                if (VectorUtils.cosineSimilarity(item.getVector(), kept) > DEDUP_SIMILARITY_THRESHOLD) {
                    isDuplicate = true;
                    log.debug("向量去重 | title={}", item.getTitle());
                    break;
                }
            }

            if (!isDuplicate) {
                result.add(item);
                keptVectors.add(item.getVector());
            }
        }

        return result;
    }

    /**
     * LLM 生成摘要（批量，最多处理前 10 条，避免 token 消耗过多）
     */
    private List<InformationItem> summarize(List<InformationItem> items) {
        if (items.isEmpty()) return items;

        int limit = Math.min(items.size(), 10);
        for (int i = 0; i < limit; i++) {
            InformationItem item = items.get(i);
            try {
                String summary = generateSummary(item);
                if (summary != null && !summary.isBlank()) {
                    item.setSummary(summary);
                }
            } catch (Exception e) {
                log.debug("摘要生成失败 | title={}", item.getTitle(), e);
            }
        }

        // 超出限制的条目，用内容前 100 字作为摘要
        for (int i = limit; i < items.size(); i++) {
            InformationItem item = items.get(i);
            if (item.getSummary() == null || item.getSummary().isBlank()) {
                String content = item.getContent();
                if (content != null && content.length() > 100) {
                    item.setSummary(content.substring(0, 100) + "...");
                } else {
                    item.setSummary(content != null ? content : "");
                }
            }
        }

        return items;
    }

    /**
     * 为单条信息生成 LLM 摘要
     */
    private String generateSummary(InformationItem item) {
        String title = item.getTitle() != null ? item.getTitle() : "";
        String content = item.getContent() != null ? item.getContent() : "";
        if (content.length() > 300) {
            content = content.substring(0, 300) + "...";
        }

        String prompt = SUMMARY_PROMPT
                .replace("{title}", title)
                .replace("{category}", item.getCategory() != null ? item.getCategory() : "")
                .replace("{content}", content);

        String result = llmClient.chatWithSystemPrompt("你是信息摘要专家。", prompt);
        if (result != null) {
            result = result.trim().replaceAll("^\"|\"$", ""); // 去掉可能的引号
        }
        return result;
    }

    /**
     * 批量 Embedding
     */
    private List<InformationItem> embed(List<InformationItem> items) {
        if (items.isEmpty()) return items;

        try {
            // 构造 embedding 文本：标题 + 摘要/内容
            List<String> texts = items.stream()
                    .map(item -> {
                        String text = item.getTitle();
                        String content = item.getSummary() != null ? item.getSummary() : item.getContent();
                        if (content != null && !content.isBlank()) {
                            text += " " + content;
                        }
                        return text;
                    })
                    .toList();

            // 批量 embedding
            List<float[]> vectors = embeddingClient.embedBatch(texts);

            // 赋值向量
            for (int i = 0; i < items.size(); i++) {
                items.get(i).setVector(vectors.get(i));
            }

            log.debug("Embedding 完成 | count={}", items.size());
        } catch (Exception e) {
            log.error("Embedding 失败", e);
            // embedding 失败不阻塞流程，只是这些信息无法做语义匹配
        }

        return items;
    }
}
