package com.youkeda.exercise.claw.ai.retrieval;

import com.youkeda.exercise.claw.agent.memory.longterm.QdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared, configuration-driven Qdrant client for Skill knowledge. */
@Component
public class QdrantClientProvider {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientProvider.class);

    private final QdrantProperties properties;

    private QdrantClient client;

    public QdrantClientProvider(QdrantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.client = new QdrantClient(
                QdrantGrpcClient.newBuilder(
                        properties.getHost(), properties.getPort(), false).build());
        log.info("QdrantClientProvider initialized: {}:{}",
                properties.getHost(), properties.getPort());
    }

    public QdrantClient getClient() {
        if (client == null) {
            throw new IllegalStateException("Qdrant client is not initialized");
        }
        return client;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
            client = null;
            log.info("QdrantClientProvider closed");
        }
    }
}
