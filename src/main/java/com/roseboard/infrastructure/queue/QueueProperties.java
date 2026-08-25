package com.roseboard.infrastructure.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roseboard.queue")
public class QueueProperties {
    /**
     * Application-wide provider: {@code memory} or {@code kafka}.
     */
    private String provider = "memory";
    private final Kafka kafka = new Kafka();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private short replicationFactor = 1;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }
    }
}
