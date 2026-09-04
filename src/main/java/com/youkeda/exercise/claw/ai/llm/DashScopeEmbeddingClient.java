package com.youkeda.exercise.claw.ai.llm;

import com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义路由使用的嵌入客户端适配器。
 *
 * <p>历史上该类直接复用 {@link LLMProperties}，把聊天模型地址拼成
 * {@code /embeddings}。切换聊天提供商后会误请求不支持嵌入接口的地址。
 * 现在统一委托给独立的 {@code memory.embedding.*} 客户端，使聊天和嵌入配置解耦。
 *
 * <p>类名暂时保留以避免扩大迁移范围；实际嵌入提供商由
 * {@link EmbeddingProperties} 决定，不再限定为 DashScope。
 */
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);

    private final com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient delegate;
    private final EmbeddingProperties embeddingProperties;

    public DashScopeEmbeddingClient(
            com.youkeda.exercise.claw.agent.memory.longterm.EmbeddingClient delegate,
            EmbeddingProperties embeddingProperties) {
        this.delegate = delegate;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return emptyVector();
        }
        try {
            return delegate.embed(text);
        } catch (Exception e) {
            log.warn("Semantic embedding unavailable: {}", e.getMessage());
            return emptyVector();
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return delegate.embedBatch(texts);
        } catch (Exception e) {
            log.warn("Semantic embedding batch unavailable | count={} | error={}",
                    texts.size(), e.getMessage());
            List<float[]> fallback = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                fallback.add(emptyVector());
            }
            return fallback;
        }
    }

    @Override
    public int getDimensions() {
        return embeddingProperties.getDimension();
    }

    private float[] emptyVector() {
        return new float[embeddingProperties.getDimension()];
    }
}
