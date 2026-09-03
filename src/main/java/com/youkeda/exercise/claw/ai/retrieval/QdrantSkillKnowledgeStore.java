package com.youkeda.exercise.claw.ai.retrieval;

import static io.qdrant.client.ValueFactory.value;

import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QdrantSkillKnowledgeStore implements SkillKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantSkillKnowledgeStore.class);

    private final QdrantClientProvider clientProvider;

    @Value("${skill.knowledge.collection:skill_knowledge}")
    private String collectionName;

    @Value("${memory.qdrant.vector-dimension:1024}")
    private int vectorDimension;

    @Value("${skill.knowledge.operation-timeout:10s}")
    private Duration operationTimeout;

    private volatile boolean initialized;
    private volatile String lastError = "not initialized";

    public QdrantSkillKnowledgeStore(QdrantClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    @PostConstruct
    public void init() {
        try {
            var client = clientProvider.getClient();
            boolean exists = client.collectionExistsAsync(collectionName, operationTimeout).get();
            if (!exists) {
                client.createCollectionAsync(collectionName,
                        Collections.VectorParams.newBuilder()
                                .setDistance(Collections.Distance.Cosine)
                                .setSize(vectorDimension)
                                .build(), operationTimeout).get();
                log.info("Created Qdrant collection: {}", collectionName);
            }
            createPayloadIndexes();
            initialized = true;
            lastError = "OK";
        } catch (Exception e) {
            initialized = false;
            lastError = rootMessage(e);
            log.warn("Skill knowledge store unavailable at startup; RAG will degrade without knowledge | collection={} | error={}",
                    collectionName, lastError);
        }
    }

    @Override
    public void upsertAll(List<SkillKnowledgeVector> points) {
        if (points == null || points.isEmpty()) return;
        try {
            List<PointStruct> qdrantPoints = new ArrayList<>(points.size());
            for (SkillKnowledgeVector point : points) {
                SkillKnowledgeChunk chunk = point.chunk();
                if (point.vector().length != vectorDimension) {
                    throw new IllegalArgumentException("Vector dimension mismatch: expected="
                            + vectorDimension + ", actual=" + point.vector().length);
                }
                qdrantPoints.add(PointStruct.newBuilder()
                        .setId(PointId.newBuilder().setUuid(chunk.chunkId()).build())
                        .setVectors(Vectors.newBuilder()
                                .setVector(Vector.newBuilder()
                                        .addAllData(toFloatList(point.vector())).build())
                                .build())
                        .putAllPayload(toPayload(chunk))
                        .build());
            }
            clientProvider.getClient()
                    .upsertAsync(collectionName, qdrantPoints, operationTimeout).get();
        } catch (Exception e) {
            throw storeFailure("upsert knowledge chunks", e);
        }
    }

    @Override
    public List<SkillKnowledgeSearchResult> search(
            float[] queryVector, Set<String> skillNames, int topK, float minScore) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) return List.of();
        if (skillNames == null || skillNames.isEmpty()
                || skillNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("At least one non-blank skillName is required");
        }
        try {
            Points.SearchPoints.Builder builder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setScoreThreshold(minScore)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            Filter.Builder filter = Filter.newBuilder()
                    .addMust(fieldCondition("enabled", Match.newBuilder().setBoolean(true).build()));
            if (skillNames != null && !skillNames.isEmpty()) {
                for (String name : skillNames) {
                    filter.addShould(fieldCondition(
                            "skillName", Match.newBuilder().setKeyword(name).build()));
                }
            }
            builder.setFilter(filter.build());

            List<ScoredPoint> points = clientProvider.getClient()
                    .searchAsync(builder.build(), operationTimeout).get();
            List<SkillKnowledgeSearchResult> results = new ArrayList<>(points.size());
            for (ScoredPoint point : points) {
                results.add(new SkillKnowledgeSearchResult(
                        point.getId().getUuid(),
                        readString(point, "skillName"),
                        readString(point, "documentId"),
                        readString(point, "content"),
                        readString(point, "contentHash"),
                        readString(point, "source"),
                        readString(point, "heading"),
                        readInteger(point, "pageNumber"),
                        readString(point, "version"),
                        point.getScore()));
            }
            return results;
        } catch (Exception e) {
            throw storeFailure("search skill knowledge", e);
        }
    }

    @Override
    public long setDocumentEnabled(String skillName, String documentId, boolean enabled) {
        Filter filter = documentFilter(skillName, documentId);
        long count = count(filter);
        if (count == 0) return 0;
        try {
            clientProvider.getClient().setPayloadAsync(
                    collectionName,
                    Map.of("enabled", value(enabled)),
                    filter,
                    true,
                    null,
                    operationTimeout).get();
            return count;
        } catch (Exception e) {
            throw storeFailure("set knowledge document activation", e);
        }
    }

    @Override
    public long softDeleteByDocument(String skillName, String documentId) {
        return setDocumentEnabled(skillName, documentId, false);
    }

    @Override
    public long hardDeleteByDocument(String skillName, String documentId) {
        Filter filter = documentFilter(skillName, documentId);
        long count = count(filter);
        if (count == 0) return 0;
        try {
            clientProvider.getClient().deleteAsync(collectionName, filter, operationTimeout).get();
            return count;
        } catch (Exception e) {
            throw storeFailure("hard delete knowledge document", e);
        }
    }

    @Override
    public long countByDocument(String skillName, String documentId) {
        return count(documentFilter(skillName, documentId));
    }

    @Override
    public KnowledgeStoreStatus status(String skillName) {
        try {
            clientProvider.getClient().healthCheckAsync(operationTimeout).get();
            Filter.Builder filterBuilder = Filter.newBuilder()
                    .addMust(fieldCondition("enabled",
                            Match.newBuilder().setBoolean(true).build()));
            if (skillName != null && !skillName.isBlank()) {
                filterBuilder.addMust(fieldCondition("skillName",
                        Match.newBuilder().setKeyword(skillName).build()));
            }
            Filter filter = filterBuilder.build();
            long points = clientProvider.getClient()
                    .countAsync(collectionName, filter, true, operationTimeout).get();
            initialized = true;
            lastError = "OK";
            return new KnowledgeStoreStatus(true, collectionName, points, "OK");
        } catch (Exception e) {
            initialized = false;
            lastError = rootMessage(e);
            return new KnowledgeStoreStatus(false, collectionName, 0, lastError);
        }
    }

    private void createPayloadIndexes() {
        createPayloadIndex("skillName", Collections.PayloadSchemaType.Keyword);
        createPayloadIndex("documentId", Collections.PayloadSchemaType.Keyword);
        createPayloadIndex("enabled", Collections.PayloadSchemaType.Bool);
    }

    private void createPayloadIndex(String field, Collections.PayloadSchemaType schemaType) {
        try {
            clientProvider.getClient().createPayloadIndexAsync(
                    collectionName, field, schemaType, null, true, null,
                    operationTimeout).get();
        } catch (Exception e) {
            log.debug("Payload index already exists or could not be created | collection={} | field={} | error={}",
                    collectionName, field, rootMessage(e));
        }
    }

    private Map<String, io.qdrant.client.grpc.JsonWithInt.Value> toPayload(
            SkillKnowledgeChunk chunk) {
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new HashMap<>();
        payload.put("skillName", value(chunk.skillName()));
        payload.put("documentId", value(chunk.documentId()));
        payload.put("chunkIndex", value(chunk.chunkIndex()));
        payload.put("content", value(chunk.content()));
        payload.put("contentHash", value(nullToEmpty(chunk.contentHash())));
        payload.put("source", value(nullToEmpty(chunk.source())));
        payload.put("heading", value(nullToEmpty(chunk.heading())));
        if (chunk.pageNumber() != null) payload.put("pageNumber", value(chunk.pageNumber()));
        payload.put("version", value(nullToEmpty(chunk.version())));
        payload.put("enabled", value(chunk.enabled()));
        return payload;
    }

    private long count(Filter filter) {
        try {
            return clientProvider.getClient()
                    .countAsync(collectionName, filter, true, operationTimeout).get();
        } catch (Exception e) {
            throw storeFailure("count knowledge chunks", e);
        }
    }

    private Filter documentFilter(String skillName, String documentId) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        return Filter.newBuilder()
                .addMust(fieldCondition("skillName",
                        Match.newBuilder().setKeyword(skillName).build()))
                .addMust(fieldCondition("documentId",
                        Match.newBuilder().setKeyword(documentId).build()))
                .build();
    }

    private Condition fieldCondition(String key, Match match) {
        return Condition.newBuilder()
                .setField(FieldCondition.newBuilder().setKey(key).setMatch(match).build())
                .build();
    }

    private String readString(ScoredPoint point, String key) {
        return point.getPayloadMap().containsKey(key)
                ? point.getPayloadMap().get(key).getStringValue() : "";
    }

    private Integer readInteger(ScoredPoint point, String key) {
        return point.getPayloadMap().containsKey(key)
                ? (int) point.getPayloadMap().get(key).getIntegerValue() : null;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return values;
    }

    private SkillKnowledgeStoreException storeFailure(String operation, Exception cause) {
        initialized = false;
        lastError = rootMessage(cause);
        return new SkillKnowledgeStoreException(
                "Failed to " + operation + ": " + lastError, cause);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
