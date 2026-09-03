package com.youkeda.exercise.claw.agent.memory.longterm;

import static io.qdrant.client.ValueFactory.value;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.HasIdCondition;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Qdrant 向量存储实现
 *
 * 向量索引：HNSW（默认）。
 */
@Component
public class QdrantMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantMemoryStore.class);

    private final QdrantProperties props;

    private QdrantClient client;

    public QdrantMemoryStore(QdrantProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(props.getHost(), props.getPort(), false)
                            .build());
            if (!ensureCollection()) {
                client.close();
                client = null;
                log.warn("Qdrant 初始化失败，长期记忆已降级 | host={}:{}",
                        props.getHost(), props.getPort());
                return;
            }
            log.info("Qdrant 连接成功 | host={}:{} | collection={}",
                    props.getHost(), props.getPort(), props.getCollection());
        } catch (Exception e) {
            log.error("Qdrant 连接失败，长期记忆功能不可用 | host={}:{}",
                    props.getHost(), props.getPort(), e);
            client = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.close();
        }
    }

    // ==================== Collection 管理 ====================

    private boolean ensureCollection() {
        try {
            Boolean exists = await(client.collectionExistsAsync(props.getCollection()));
            if (Boolean.TRUE.equals(exists)) {
                log.debug("Collection 已存在 | name={}", props.getCollection());
                return true;
            }
        } catch (Exception e) {
            log.warn("检查 Collection 存在性失败，跳过长期记忆初始化 | error={}",
                    rootMessage(e));
            return false;
        }

        try {
            await(client.createCollectionAsync(props.getCollection(),
                    VectorParams.newBuilder()
                            .setSize(props.getVectorDimension())
                            .setDistance(Distance.Cosine)
                            .build()
            ));
            log.info("Collection 创建成功 | name={}", props.getCollection());
            return true;
        } catch (Exception e) {
            log.error("Collection 创建失败 | name={} | error={}",
                    props.getCollection(), rootMessage(e));
            return false;
        }
    }

    // ==================== 写入 ====================

    @Override
    public boolean upsert(MemoryItem item, float[] vector) {
        if (client == null) {
            log.warn("Qdrant 不可用，跳过写入 | id={}", item.id());
            return false;
        }
        try {
            PointStruct point = PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setUuid(item.id()).build())
                    .setVectors(Vectors.newBuilder()
                            .setVector(Vector.newBuilder().addAllData(toFloatList(vector)).build())
                            .build())
                    .putAllPayload(buildPayload(item))
                    .build();

            await(client.upsertAsync(props.getCollection(), List.of(point)));
            log.debug("记忆写入成功 | id={}", item.id());
            return true;
        } catch (Exception e) {
            log.error("记忆写入失败 | id={}", item.id(), e);
            return false;
        }
    }

    // ==================== 语义检索 ====================

    @Override
    public List<MemoryItem> search(float[] queryVector, int topK) {
        return toItems(searchScoredInternal(queryVector, topK, null, null));
    }

    @Override
    public List<MemoryItem> search(float[] queryVector, int topK,
                                   float minScore) {
        return toItems(searchScored(queryVector, topK, minScore));
    }

    @Override
    public List<MemorySearchResult> searchScored(float[] queryVector,
                                                 int topK, float minScore) {
        return searchScoredInternal(queryVector, topK, null, minScore);
    }

    @Override
    public List<MemoryItem> search(float[] queryVector, int topK,
                                    MemoryCategory category) {
        return toItems(searchScoredInternal(queryVector, topK, category, null));
    }

    private List<MemorySearchResult> searchScoredInternal(
            float[] queryVector, int topK,
            MemoryCategory category, Float minScore) {
        if (client == null) {
            log.warn("Qdrant 不可用，返回空结果");
            return List.of();
        }
        try {
            Filter filter = buildCategoryFilter(category);

            SearchPoints.Builder requestBuilder = SearchPoints.newBuilder()
                    .setCollectionName(props.getCollection())
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setFilter(filter);
            if (minScore != null) {
                requestBuilder.setScoreThreshold(minScore);
            }

            List<ScoredPoint> results = await(client.searchAsync(requestBuilder.build()));
            List<MemorySearchResult> items = new ArrayList<>();
            for (ScoredPoint sp : results) {
                MemoryItem item = payloadToMemoryItem(sp.getId().getUuid(), sp.getPayloadMap());
                if (item != null) {
                    items.add(new MemorySearchResult(item, sp.getScore()));
                }
            }
            return items;
        } catch (Exception e) {
            log.error("语义检索失败", e);
            return List.of();
        }
    }

    private List<MemoryItem> toItems(List<MemorySearchResult> results) {
        return results.stream().map(MemorySearchResult::item).toList();
    }

    // ==================== 全量查询 ====================

    @Override
    public List<MemoryItem> getAll() {
        if (client == null) return List.of();
        try {
            ScrollPoints request = ScrollPoints.newBuilder()
                    .setCollectionName(props.getCollection())
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setLimit(1000)
                    .build();

            ScrollResponse response = await(client.scrollAsync(request));
            List<MemoryItem> items = new ArrayList<>();
            for (RetrievedPoint rp : response.getResultList()) {
                MemoryItem item = payloadToMemoryItem(rp.getId().getUuid(), rp.getPayloadMap());
                if (item != null) items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("全量查询失败", e);
            return List.of();
        }
    }

    @Override
    public MemoryItem findByTopicKey(String topicKey) {
        if (client == null || topicKey == null || topicKey.isBlank()) return null;
        try {
            ScrollPoints request = ScrollPoints.newBuilder()
                    .setCollectionName(props.getCollection())
                    .setFilter(buildTopicFilter(topicKey))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setLimit(10)
                    .build();
            return await(client.scrollAsync(request)).getResultList().stream()
                    .map(point -> payloadToMemoryItem(
                            point.getId().getUuid(), point.getPayloadMap()))
                    .filter(item -> item != null)
                    .max(java.util.Comparator.comparing(MemoryItem::updatedAt))
                    .orElse(null);
        } catch (Exception e) {
            log.error("按主题查询记忆失败 | topicKey={}", topicKey, e);
            return null;
        }
    }

    // ==================== 删除 ====================

    @Override
    public boolean delete(String memoryId) {
        if (client == null) return false;
        try {
            Filter filter = buildIdFilter(memoryId);
            Long matches = await(client.countAsync(props.getCollection(), filter, true, null));
            if (matches == 0) {
                log.warn("记忆不存在 | id={}", memoryId);
                return false;
            }
            await(client.deleteAsync(props.getCollection(), filter));
            log.debug("记忆删除成功 | id={}", memoryId);
            return true;
        } catch (Exception e) {
            log.error("记忆删除失败 | id={}", memoryId, e);
            return false;
        }
    }

    @Override
    public void clear() {
        if (client == null) return;
        try {
            // 清空所有记忆
            await(client.deleteAsync(props.getCollection(),
                    Filter.newBuilder().build()));
            log.info("所有记忆已清空");
        } catch (Exception e) {
            log.error("清空记忆失败", e);
        }
    }

    @Override
    public int count() {
        if (client == null) return 0;
        try {
            Long result = await(client.countAsync(
                    props.getCollection(),
                    Filter.newBuilder().build(),
                    true,
                    null
            ));
            return result.intValue();
        } catch (Exception e) {
            log.error("统计记忆数量失败", e);
            return 0;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建分类过滤条件（Condition 包装 FieldCondition）
     */
    private Filter buildCategoryFilter(MemoryCategory category) {
        if (category == null) return Filter.newBuilder().build();
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("category")
                                .setMatch(Match.newBuilder().setKeyword(category.name()).build())
                                .build())
                        .build())
                .build();
    }

    private Filter buildIdFilter(String memoryId) {
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setHasId(HasIdCondition.newBuilder()
                                .addHasId(PointId.newBuilder().setUuid(memoryId).build())
                                .build())
                        .build())
                .build();
    }

    private Filter buildTopicFilter(String topicKey) {
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("topicKey")
                                .setMatch(Match.newBuilder().setKeyword(topicKey).build())
                                .build())
                        .build())
                .build();
    }

    /**
     * 构建 Qdrant payload（元数据）
     */
    private Map<String, Value> buildPayload(MemoryItem item) {
        Map<String, Value> payload = new HashMap<>();
        payload.put("category", value(item.category().name()));
        payload.put("topicKey", value(item.topicKey()));
        payload.put("content", value(item.content()));
        payload.put("evidence", value(item.evidence()));
        payload.put("importance", value(item.importance()));
        payload.put("confidence", value(item.confidence()));
        payload.put("source", value(item.source().name()));
        payload.put("createdAt", value(item.createdAt().toEpochMilli()));
        payload.put("updatedAt", value(item.updatedAt().toEpochMilli()));
        payload.put("hitCount", value(item.hitCount()));
        return payload;
    }

    /**
     * 从 Qdrant payload 还原 MemoryItem
     */
    private MemoryItem payloadToMemoryItem(String pointId, Map<String, Value> payload) {
        try {
            String categoryStr = getString(payload, "category");
            String topicKey = getString(payload, "topicKey");
            String content = getString(payload, "content");
            String evidence = getString(payload, "evidence");
            float importance = getDouble(payload, "importance");
            float confidence = payload.containsKey("confidence")
                    ? getDouble(payload, "confidence") : 0.5f;
            String sourceStr = getString(payload, "source");
            long createdMs = getLong(payload, "createdAt");
            long updatedMs = getLong(payload, "updatedAt");
            int hitCount = (int) getDouble(payload, "hitCount");

            return new MemoryItem(
                    pointId,
                    MemoryCategory.valueOf(categoryStr),
                    topicKey, content,
                    evidence.isBlank() ? content : evidence,
                    importance, confidence,
                    MemorySource.valueOf(sourceStr),
                    Instant.ofEpochMilli(createdMs),
                    Instant.ofEpochMilli(updatedMs),
                    hitCount);
        } catch (Exception e) {
            log.warn("payload 解析失败 | pointId={}", pointId, e);
            return null;
        }
    }

    private String getString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v == null) return "";
        try {
            return v.getStringValue();
        } catch (Exception e) {
            return "";
        }
    }

    private float getDouble(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v == null) return 0f;
        try {
            if (v.hasDoubleValue()) return (float) v.getDoubleValue();
            if (v.hasIntegerValue()) return v.getIntegerValue();
        } catch (Exception ignored) {
        }
        return 0f;
    }

    private long getLong(Map<String, Value> payload, String key) {
        Value value = payload.get(key);
        if (value == null) return 0L;
        try {
            if (value.hasIntegerValue()) return value.getIntegerValue();
            if (value.hasDoubleValue()) return (long) value.getDoubleValue();
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private <T> T await(Future<T> future) throws Exception {
        long timeoutMillis = Math.max(1L, props.getOperationTimeout().toMillis());
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "Qdrant operation timed out after " + timeoutMillis + "ms", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
