/*
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Roseboard: configuration prefix roseboard.queue.kafka; no Spring component.
 */
package com.roseboard.infrastructure.queue.adapter.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class KafkaQueueProperties {

    private String bootstrapServers = "localhost:9092";
    private boolean sslEnabled;
    private String sslTruststoreLocation = "";
    private String sslTruststorePassword = "";
    private String sslKeystoreLocation = "";
    private String sslKeystorePassword = "";
    private String sslKeyPassword = "";
    private String acks = "all";
    private int retries = 1;
    private String compressionType = "none";
    private int batchSize = 16384;
    private long lingerMs = 1;
    private int maxRequestSize = 1_048_576;
    private int maxInFlightRequestsPerConnection = 5;
    private long bufferMemory = 33_554_432;
    private short replicationFactor = 1;
    private int maxPollRecords = 8192;
    private int maxPollIntervalMs = 300_000;
    private int maxPartitionFetchBytes = 16_777_216;
    private int fetchMaxBytes = 134_217_728;
    private int requestTimeoutMs = 30_000;
    private int sessionTimeoutMs = 10_000;
    private String autoOffsetReset = "earliest";
    private boolean useConfluent;
    private String sslAlgorithm = "";
    private String saslMechanism = "";
    private String saslConfig = "";
    private String securityProtocol = "";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthEndpointUrl = "";
    private String oauthScope = "";
    private String otherInline = "";
    private String consumerPropertiesPerTopicInline = "";
    private Map<String, Map<String, String>> consumerPropertiesPerTopic = new HashMap<>();
    private Map<String, String> other = new LinkedHashMap<>();

    public KafkaQueueProperties bootstrapServers(String bootstrapServers) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
        return this;
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    public short replicationFactor() {
        return replicationFactor;
    }

    public KafkaQueueProperties replicationFactor(short replicationFactor) {
        this.replicationFactor = replicationFactor;
        return this;
    }

    public int requestTimeoutMs() {
        return requestTimeoutMs;
    }

    public KafkaQueueProperties requestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
        return this;
    }

    public KafkaQueueProperties otherInline(String otherInline) {
        this.otherInline = otherInline == null ? "" : otherInline;
        return this;
    }

    public KafkaQueueProperties putOther(String key, String value) {
        other.put(key, value);
        return this;
    }

    public KafkaQueueProperties putConsumerTopicProperty(String topicPrefix, String key, String value) {
        consumerPropertiesPerTopic
                .computeIfAbsent(topicPrefix, ignored -> new LinkedHashMap<>())
                .put(key, value);
        return this;
    }

    public Properties toConsumerProps(String topic) {
        Properties props = toProps();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxPartitionFetchBytes);
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, fetchMaxBytes);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        if (topic != null) {
            Map<String, String> topicProps = consumerPropertiesPerTopic.get(topic);
            if (topicProps == null) {
                for (Map.Entry<String, Map<String, String>> entry : consumerPropertiesPerTopic.entrySet()) {
                    if (topic.startsWith(entry.getKey())) {
                        topicProps = entry.getValue();
                        break;
                    }
                }
            }
            if (topicProps != null) {
                topicProps.forEach(props::put);
            }
        }
        // Safety invariant: never allow auto-commit even if custom props try to enable it.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

    public Properties toProducerProps() {
        Properties props = toProps();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.RETRIES_CONFIG, retries);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, maxRequestSize);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlightRequestsPerConnection);
        return props;
    }

    public Properties toAdminProps() {
        Properties props = toProps();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.RETRIES_CONFIG, retries);
        return props;
    }

    Properties toProps() {
        Properties props = new Properties();
        if (useConfluent) {
            props.put("ssl.endpoint.identification.algorithm", sslAlgorithm);
            props.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
            if ("OAUTHBEARER".equalsIgnoreCase(saslMechanism)) {
                applyOauthBearerProps(props);
            } else if (saslConfig != null && !saslConfig.isBlank()) {
                props.put(SaslConfigs.SASL_JAAS_CONFIG, saslConfig);
            }
        }
        props.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        parseInline(otherInline).forEach(props::put);
        other.forEach(props::put);
        if (sslEnabled) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
            props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);
            props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, sslTruststorePassword);
            props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, sslKeystoreLocation);
            props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, sslKeystorePassword);
            props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, sslKeyPassword);
        }
        return props;
    }

    private void applyOauthBearerProps(Properties props) {
        if (isBlank(oauthClientId) || isBlank(oauthClientSecret) || isBlank(oauthEndpointUrl)) {
            throw new IllegalStateException("OAUTHBEARER requires client-id, client-secret, and endpoint-url");
        }
        StringBuilder jaas = new StringBuilder(
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
                        + " clientId=\"" + escapeJaas(oauthClientId) + "\""
                        + " clientSecret=\"" + escapeJaas(oauthClientSecret) + "\"");
        if (!isBlank(oauthScope)) {
            jaas.append(" scope=\"").append(escapeJaas(oauthScope)).append("\"");
        }
        jaas.append(";");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, jaas.toString());
        props.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS,
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler");
        props.put(SaslConfigs.SASL_OAUTHBEARER_TOKEN_ENDPOINT_URL, oauthEndpointUrl);
    }

    static Map<String, String> parseInline(String inline) {
        Map<String, String> result = new LinkedHashMap<>();
        if (inline == null || inline.isBlank()) {
            return result;
        }
        for (String part : inline.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                result.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
