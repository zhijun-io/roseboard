package com.roseboard.infrastructure.queue;

import com.roseboard.infrastructure.queue.spi.QueueAdmin;
import com.roseboard.infrastructure.queue.spi.QueueConsumerProvider;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProperties;
import com.roseboard.infrastructure.queue.adapter.kafka.KafkaQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueProvider;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.adapter.memory.MemoryQueueAdmin;
import com.roseboard.infrastructure.queue.adapter.memory.NoOpQueueAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AutoConfiguration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "roseboard.queue", name = "provider", havingValue = "memory", matchIfMissing = true)
    InMemoryQueueStorage inMemoryQueueStorage() {
        return new InMemoryQueueStorage();
    }

    @Bean
    @ConditionalOnProperty(prefix = "roseboard.queue", name = "provider", havingValue = "memory", matchIfMissing = true)
    InMemoryQueueProvider memoryQueueProvider(InMemoryQueueStorage storage) {
        return new InMemoryQueueProvider(storage);
    }

    @Bean
    @ConditionalOnProperty(prefix = "roseboard.queue", name = "provider", havingValue = "kafka")
    KafkaQueueProvider kafkaQueueProvider(QueueProperties properties) {
        KafkaQueueProperties kafka = new KafkaQueueProperties()
                .bootstrapServers(properties.getKafka().getBootstrapServers())
                .replicationFactor(properties.getKafka().getReplicationFactor());
        return new KafkaQueueProvider(kafka);
    }

    @Bean
    @ConditionalOnMissingBean(QueueProducerProvider.class)
    QueueProducerProvider queueProducerProvider(QueueProperties properties,
                                                ObjectProvider<InMemoryQueueProvider> memory,
                                                ObjectProvider<KafkaQueueProvider> kafka) {
        return (QueueProducerProvider) selectProvider(properties, memory, kafka);
    }

    @Bean
    @ConditionalOnMissingBean(QueueConsumerProvider.class)
    QueueConsumerProvider queueConsumerProvider(QueueProperties properties,
                                                ObjectProvider<InMemoryQueueProvider> memory,
                                                ObjectProvider<KafkaQueueProvider> kafka) {
        return (QueueConsumerProvider) selectProvider(properties, memory, kafka);
    }

    @Bean
    @ConditionalOnProperty(prefix = "roseboard.queue", name = "provider", havingValue = "memory", matchIfMissing = true)
    MemoryQueueAdmin memoryQueueAdmin() {
        return new MemoryQueueAdmin();
    }

    @Bean
    @ConditionalOnMissingBean(QueueAdmin.class)
    QueueAdmin queueAdmin(QueueProperties properties,
                          ObjectProvider<KafkaQueueProvider> kafka,
                          ObjectProvider<MemoryQueueAdmin> memoryAdmin) {
        if ("kafka".equalsIgnoreCase(properties.getProvider())) {
            KafkaQueueProvider provider = kafka.getIfAvailable();
            if (provider == null) {
                throw new IllegalStateException("kafka provider required");
            }
            return provider.admin();
        }
        MemoryQueueAdmin admin = memoryAdmin.getIfAvailable();
        return admin != null ? admin : NoOpQueueAdmin.INSTANCE;
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService queueConsumerExecutor() {
        return Executors.newCachedThreadPool(r -> new Thread(r, "rb-queue-consumer"));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService queueWorkerExecutor() {
        return Executors.newCachedThreadPool(r -> new Thread(r, "rb-queue-worker"));
    }

    @Bean
    @ConditionalOnMissingBean(QueueCoordinator.class)
    QueueCoordinator queueCoordinator(QueueProperties properties,
                                            QueueConsumerProvider consumerProvider,
                                            QueueProducerProvider producerProvider,
                                            QueueAdmin admin,
                                            ExecutorService queueConsumerExecutor,
                                            ExecutorService queueWorkerExecutor) {
        return new QueueCoordinator(properties, consumerProvider, producerProvider, admin,
                queueConsumerExecutor, queueWorkerExecutor);
    }

    private static Object selectProvider(QueueProperties properties,
                                         ObjectProvider<InMemoryQueueProvider> memory,
                                         ObjectProvider<KafkaQueueProvider> kafka) {
        String provider = properties.getProvider() == null ? "memory" : properties.getProvider().trim().toLowerCase();
        return switch (provider) {
            case "memory" -> {
                InMemoryQueueProvider m = memory.getIfAvailable();
                if (m == null) {
                    throw new IllegalStateException("Unsupported or missing queue provider: memory");
                }
                yield m;
            }
            case "kafka" -> {
                KafkaQueueProvider k = kafka.getIfAvailable();
                if (k == null) {
                    throw new IllegalStateException("Unsupported or missing queue provider: kafka");
                }
                yield k;
            }
            default -> throw new IllegalStateException("Unsupported queue provider: " + provider);
        };
    }
}
