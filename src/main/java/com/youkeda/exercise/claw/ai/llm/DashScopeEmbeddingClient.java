package com.youkeda.exercise.claw.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 嵌入向量客户端实现
 */
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_BATCH_SIZE = 10;
    private static final String MODEL_NAME = "text-embedding-v2";
    private static final int DEFAULT_DIMENSIONS = 1536;

    private final LLMProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DashScopeEmbeddingClient(LLMProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[DEFAULT_DIMENSIONS];
        }

        try {
            String requestBody = buildEmbeddingRequest(List.of(text));
            String response = callEmbeddingAPI(requestBody);
            List<float[]> embeddings = parseEmbeddingResponse(response);

            if (embeddings.isEmpty()) {
                log.warn("Embedding API returned empty result");
                return new float[DEFAULT_DIMENSIONS];
            }

            return embeddings.get(0);
        } catch (Exception e) {
            log.error("Embedding failed: {}", e.getMessage());
            return new float[DEFAULT_DIMENSIONS];
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        List<float[]> allEmbeddings = new ArrayList<>();

        // 批量处理
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));

            try {
                String requestBody = buildEmbeddingRequest(batch);
                String response = callEmbeddingAPI(requestBody);
                List<float[]> batchEmbeddings = parseEmbeddingResponse(response);
                allEmbeddings.addAll(batchEmbeddings);
            } catch (Exception e) {
                log.error("Embedding batch failed at index {}: {}", i, e.getMessage());
                // 填充默认向量
                for (int j = 0; j < batch.size(); j++) {
                    allEmbeddings.add(new float[DEFAULT_DIMENSIONS]);
                }
            }
        }

        return allEmbeddings;
    }

    @Override
    public int getDimensions() {
        return DEFAULT_DIMENSIONS;
    }

    private String buildEmbeddingRequest(List<String> texts) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("model", MODEL_NAME);

            var inputArray = root.putArray("input");
            for (String text : texts) {
                inputArray.add(text);
            }

            var parameters = root.putObject("parameters");
            parameters.put("dimension", DEFAULT_DIMENSIONS);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build embedding request", e);
        }
    }

    private String callEmbeddingAPI(String requestBody) throws IOException, InterruptedException {
        String url = properties.getBaseUrl().replace("chat/completions", "embeddings");
        if (!url.contains("embeddings")) {
            url = properties.getBaseUrl() + "/embeddings";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Embedding API returned status: " + response.statusCode()
                    + ", body: " + response.body());
        }

        return response.body();
    }

    private List<float[]> parseEmbeddingResponse(String responseBody) throws IOException {
        List<float[]> embeddings = new ArrayList<>();

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode dataArray = root.get("data");

        if (dataArray == null || !dataArray.isArray()) {
            log.warn("Invalid embedding response: missing 'data' array");
            return embeddings;
        }

        for (JsonNode item : dataArray) {
            JsonNode embeddingArray = item.get("embedding");
            if (embeddingArray == null || !embeddingArray.isArray()) {
                continue;
            }

            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.get(i).asDouble(0.0);
            }
            embeddings.add(embedding);
        }

        return embeddings;
    }
}
