package com.youkeda.exercise.claw.agent.memory.longterm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** OpenAI-compatible embedding endpoint settings. */
@Component
@ConfigurationProperties(prefix = "memory.embedding")
public class EmbeddingProperties {

    private String baseUrl = "http://localhost:8082";
    private String apiKey = "";
    private String model = "bge-m3";
    private int dimension = 1024;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Circuit circuit = new Circuit();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Circuit getCircuit() { return circuit; }
    public void setCircuit(Circuit circuit) { this.circuit = circuit; }

    public static class Circuit {
        private int failureThreshold = 3;
        private Duration openDuration = Duration.ofSeconds(10);

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
    }
}
