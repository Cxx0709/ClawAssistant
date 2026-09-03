package com.youkeda.exercise.claw.agent.memory.longterm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Qdrant 向量数据库配置
 */
@Component
@ConfigurationProperties(prefix = "memory.qdrant")
public class QdrantProperties {

    /** Qdrant 服务地址 */
    private String host = "localhost";

    /** Qdrant gRPC 端口 */
    private int port = 6334;

    /** Collection 名称 */
    private String collection = "claw_memories";

    /** 向量维度（需与 Embedding 模型一致，BGE-M3 默认 1024） */
    private int vectorDimension = 1024;

    /** 可选记忆服务不可用时，不得长时间阻塞对话主链路。 */
    private Duration operationTimeout = Duration.ofSeconds(1);

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    public void setOperationTimeout(Duration operationTimeout) {
        this.operationTimeout = operationTimeout;
    }
}
