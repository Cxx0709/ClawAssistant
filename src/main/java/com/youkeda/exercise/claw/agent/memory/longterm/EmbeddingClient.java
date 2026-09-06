package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** OpenAI-compatible embedding client with timeout and recoverable circuit breaker. */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BATCH_SIZE = 10;

    enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final EmbeddingProperties props;
    private final HttpClient httpClient;
    private final HttpClient healthCheckClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private CircuitState circuitState = CircuitState.CLOSED;
    private int consecutiveFailures;
    private Instant openUntil = Instant.EPOCH;
    private boolean halfOpenProbeInFlight;

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingClient(EmbeddingProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper,
                HttpClient.newBuilder().connectTimeout(props.getRequestTimeout()).build(),
                HttpClient.newBuilder().connectTimeout(HEALTH_CHECK_TIMEOUT).build(),
                Clock.systemUTC());
    }

    EmbeddingClient(EmbeddingProperties props,
                    ObjectMapper objectMapper,
                    HttpClient httpClient,
                    HttpClient healthCheckClient,
                    Clock clock) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.healthCheckClient = healthCheckClient;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        checkHealth();
    }

    private void checkHealth() {
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) return;
        try {
            String url = endpoint();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"" + props.getModel() + "\",\"input\":[\"ping\"]}"))
                    .build();
            HttpResponse<String> response = healthCheckClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                onSuccess();
                log.info("Embedding 服务连接成功 | url={} | status={}", url, response.statusCode());
            } else {
                openCircuit();
                log.warn("Embedding 服务预检查失败，熔断器将在冷却后自动探测 | status={}",
                        response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            openCircuit();
            log.warn("Embedding 服务预检查被中断，已暂时打开熔断器");
        } catch (Exception e) {
            openCircuit();
            log.warn("Embedding 服务预检查失败，熔断器将在冷却后自动探测 | error={}",
                    e.getMessage());
        }
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding text must not be blank");
        }
        if (texts.size() <= MAX_BATCH_SIZE) {
            return doEmbedBatch(texts);
        }
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
            all.addAll(doEmbedBatch(texts.subList(i, end)));
        }
        return all;
    }

    private List<float[]> doEmbedBatch(List<String> texts) {
        beforeRequest();

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(props.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequest(texts)));
            if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + props.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Embedding API returned status " + response.statusCode());
            }

            List<float[]> vectors = parseResponse(response.body(), texts.size());
            onSuccess();
            log.debug("Embedding succeeded | count={} | dimension={}",
                    vectors.size(), vectors.get(0).length);
            return vectors;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure();
            throw new IllegalStateException("Embedding call interrupted", e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            onFailure();
            log.debug("Embedding call failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            onFailure();
            log.warn("Embedding call failed", e);
            throw new IllegalStateException("Embedding call failed", e);
        }
    }

    synchronized CircuitState circuitState() {
        refreshOpenState();
        return circuitState;
    }

    public synchronized String circuitStateName() {
        refreshOpenState();
        return circuitState.name();
    }

    private synchronized void beforeRequest() {
        refreshOpenState();
        if (circuitState == CircuitState.OPEN) {
            throw new IllegalStateException("Embedding circuit is open until " + openUntil);
        }
        if (circuitState == CircuitState.HALF_OPEN) {
            if (halfOpenProbeInFlight) {
                throw new IllegalStateException("Embedding circuit half-open probe is in progress");
            }
            halfOpenProbeInFlight = true;
        }
    }

    private synchronized void refreshOpenState() {
        if (circuitState == CircuitState.OPEN && !clock.instant().isBefore(openUntil)) {
            circuitState = CircuitState.HALF_OPEN;
            halfOpenProbeInFlight = false;
            log.info("Embedding circuit transitioned to HALF_OPEN");
        }
    }

    private synchronized void onSuccess() {
        circuitState = CircuitState.CLOSED;
        consecutiveFailures = 0;
        halfOpenProbeInFlight = false;
    }

    private synchronized void onFailure() {
        halfOpenProbeInFlight = false;
        if (circuitState == CircuitState.HALF_OPEN) {
            openCircuit();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= Math.max(1, props.getCircuit().getFailureThreshold())) {
            openCircuit();
        }
    }

    private synchronized void openCircuit() {
        circuitState = CircuitState.OPEN;
        halfOpenProbeInFlight = false;
        openUntil = clock.instant().plus(props.getCircuit().getOpenDuration());
        log.warn("Embedding circuit transitioned to OPEN | retryAfter={}", openUntil);
    }

    private String endpoint() {
        return props.getBaseUrl().replaceAll("/+$", "") + "/v1/embeddings";
    }

    private String buildRequest(List<String> texts) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", props.getModel());
        ArrayNode input = root.putArray("input");
        texts.forEach(input::add);
        return objectMapper.writeValueAsString(root);
    }

    private List<float[]> parseResponse(String responseBody, int expectedCount) throws Exception {
        JsonNode data = objectMapper.readTree(responseBody).get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Embedding response has no data array");
        }

        float[][] ordered = new float[expectedCount][];
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray()) {
                throw new IllegalStateException("Embedding response contains an invalid vector");
            }
            int index = item.has("index") ? item.path("index").asInt(-1) : fallbackIndex;
            fallbackIndex++;
            if (index < 0 || index >= expectedCount || ordered[index] != null) {
                throw new IllegalStateException("Invalid embedding response index: " + index);
            }
            float[] vector = new float[embedding.size()];
            double norm = 0d;
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
                if (!Float.isFinite(vector[i])) {
                    throw new IllegalStateException("Embedding contains a non-finite value");
                }
                norm += vector[i] * vector[i];
            }
            if (vector.length != props.getDimension()) {
                throw new IllegalStateException("Embedding dimension mismatch: expected="
                        + props.getDimension() + ", actual=" + vector.length);
            }
            if (norm == 0d) {
                throw new IllegalStateException("Embedding API returned a zero vector");
            }
            ordered[index] = vector;
        }

        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IllegalStateException("Embedding response count mismatch");
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
