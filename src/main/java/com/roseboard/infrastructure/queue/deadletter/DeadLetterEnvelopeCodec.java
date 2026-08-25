package com.roseboard.infrastructure.queue.deadletter;

import com.roseboard.infrastructure.queue.QueueMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class DeadLetterEnvelopeCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeadLetterEnvelopeCodec() {
    }

    static byte[] encode(QueueMessage message, String sourceTopic, Integer sourcePartition, Throwable error) {
        DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                message.getKey(),
                Base64.getEncoder().encodeToString(message.getData()),
                encodeHeaders(message.getHeaders()),
                sourceTopic,
                sourcePartition,
                error == null ? null : error.getClass().getName(),
                error == null ? null : error.getMessage());
        try {
            return MAPPER.writeValueAsBytes(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to encode dead letter envelope", e);
        }
    }

    static DeadLetterEnvelope decode(byte[] payload) {
        try {
            return MAPPER.readValue(payload, DeadLetterEnvelope.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to decode dead letter envelope", e);
        }
    }

    private static Map<String, String> encodeHeaders(Map<String, byte[]> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        headers.forEach((key, value) ->
                encoded.put(key, value == null ? "" : Base64.getEncoder().encodeToString(value)));
        return encoded;
    }
}
