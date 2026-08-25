package com.roseboard.infrastructure.message;

import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.QueueMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecRegistryJsonTest {

    @Test
    void encodesWithJsonDefaultWhenContentTypeOmitted() {
        CodecRegistry registry = sampleRegistry();
        TelemetrySample value = new TelemetrySample(21L, "celsius");

        MessageEnvelope envelope = registry.encode(EncodeRequest.builder()
                .messageId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .key("device-1")
                .messageType("telemetry.post")
                .metadata(Map.of("tenantId", "t1"))
                .createdAt(10L)
                .value(value)
                .build());

        assertEquals(ContentType.APPLICATION_JSON, envelope.descriptor().contentType());
        assertEquals("telemetry.v1", envelope.descriptor().schemaId());
        assertEquals(1, envelope.descriptor().schemaVersion());
        assertEquals("t1", envelope.metadata().get("tenantId"));

         TelemetrySample decoded = registry.decode(envelope, TelemetrySample.class);
        assertEquals(value, decoded);
    }

    @Test
    void reportsCapabilitiesAndRejectsUnknownDescriptors() {
        CodecRegistry registry = sampleRegistry();
        assertTrue(registry.supports("telemetry.post", ContentType.APPLICATION_JSON, "telemetry.v1", 1));
        assertFalse(registry.supports("telemetry.post", ContentType.APPLICATION_X_PROTOBUF, "telemetry.v1", 1));
        assertEquals(ContentType.APPLICATION_JSON, registry.defaultContentType("telemetry.post"));

        MessageDescriptor unknown = MessageDescriptor.json("unknown.msg", "unknown.v1", 1);
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(unknown)
                .createdAt(1L)
                .payload("{}".getBytes(StandardCharsets.UTF_8))
                .build();

        MessageContractException unknownType = assertThrows(MessageContractException.class, () ->
                registry.decode(envelope, Map.class));
        assertEquals(MessageErrorCode.UNKNOWN_MESSAGE_TYPE, unknownType.errorCode());

        MessageContractException unsupported = assertThrows(MessageContractException.class, () ->
                ContentType.fromMediaType("application/cbor"));
        assertEquals(MessageErrorCode.UNSUPPORTED_CONTENT_TYPE, unsupported.errorCode());
    }

    @Test
    void rejectsJavaClassNameDrivenDecoding() {
        CodecRegistry registry = sampleRegistry();
        MessageEnvelope envelope = registry.encode(EncodeRequest.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .messageType("telemetry.post")
                .createdAt(1L)
                .value(new TelemetrySample(1L, "celsius"))
                .build());

        MessageContractException exception = assertThrows(MessageContractException.class, () ->
                registry.decode(envelope, Class.forName("java.util.HashMap")));
        assertEquals(MessageErrorCode.SCHEMA_VALIDATION, exception.errorCode());
    }

    @Test
    void failsStartupOnDuplicateOrMissingJsonDefaultForNonExplicitMessages() {
        MessageContractException duplicate = assertThrows(MessageContractException.class, () ->
                CodecRegistry.builder()
                        .registerJson("telemetry.post", "telemetry.v1", 1, TelemetrySample.class)
                        .registerJson("telemetry.post", "telemetry.v1", 1, TelemetrySample.class)
                        .build());
        assertEquals(MessageErrorCode.DUPLICATE_REGISTRATION, duplicate.errorCode());
        assertTrue(duplicate.getMessage().contains("telemetry.v1"));

        MessageContractException missingDefault = assertThrows(MessageContractException.class, () ->
                CodecRegistry.builder()
                        .registerProtobuf("telemetry.post", "telemetry.v1", 1, TelemetrySample.class,
                                unusedCodec())
                        .build());
        assertEquals(MessageErrorCode.MISSING_DEFAULT_CODEC, missingDefault.errorCode());

        CodecRegistry protobufOnly = CodecRegistry.builder()
                .registerProtobufOnly("internal.event", "internal.v1", 1, bytesPassthrough())
                .build();
        assertEquals(ContentType.APPLICATION_JSON, protobufOnly.systemDefaultContentType());
        assertEquals(ContentType.APPLICATION_X_PROTOBUF, protobufOnly.defaultContentType("internal.event"));
    }

    @Test
    void queueCarriesEncodedBytesUnchanged() {
        CodecRegistry registry = CodecRegistry.builder()
                .registerJson("sample.event", "sample", 1, SamplePayload.class)
                .build();

        MessageEnvelope encoded = registry.encode(EncodeRequest.builder()
                .messageId(UUID.randomUUID())
                .messageType("sample.event")
                .contentType(ContentType.APPLICATION_JSON)
                .schemaId("sample")
                .schemaVersion(1)
                .key(UUID.randomUUID().toString())
                .value(new SamplePayload("rose", 2))
                .build());

        QueueMessage queued = toQueueMessage(encoded);
        assertArrayEquals(encoded.payload(), queued.getData());

        QueueMessage roundTrip = new DefaultQueueMessage(queued);
        assertArrayEquals(encoded.payload(), roundTrip.getData());

        SamplePayload decoded = registry.decode(toEnvelope(roundTrip, encoded.descriptor()), SamplePayload.class);
        assertEquals("rose", decoded.name());
        assertEquals(2, decoded.count());
    }

    @Test
    void declaresOnlyJsonAndProtobufAsGeneralFormats() {
        CodecRegistry registry = sampleRegistry();
        assertEquals(
                java.util.Set.of(ContentType.APPLICATION_JSON, ContentType.APPLICATION_X_PROTOBUF),
                registry.supportedGeneralContentTypes());
        assertEquals(ContentType.APPLICATION_JSON, registry.systemDefaultContentType());
    }

    private static CodecRegistry sampleRegistry() {
        return CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, TelemetrySample.class)
                .build();
    }

    private static ProtobufCodec unusedCodec() {
        return new ProtobufCodec() {
            @Override
            public Object parse(byte[] payload) {
                return payload;
            }

            @Override
            public byte[] encode(Object value) {
                return new byte[0];
            }
        };
    }

    private static ProtobufCodec bytesPassthrough() {
        return new ProtobufCodec() {
            @Override
            public Object parse(byte[] payload) {
                return payload;
            }

            @Override
            public byte[] encode(Object value) {
                if (value instanceof byte[] bytes) {
                    return bytes;
                }
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                        "Protobuf-only encoder expects byte[]");
            }
        };
    }

    record TelemetrySample(long value, String unit) {
    }

    record SamplePayload(String name, int count) {
    }

    private static QueueMessage toQueueMessage(MessageEnvelope envelope) {
        Map<String, byte[]> headers = new HashMap<>();
        envelope.headers().forEach(headers::put);
        return new DefaultQueueMessage(envelope.key(), envelope.payload(), headers);
    }

    private static MessageEnvelope toEnvelope(QueueMessage message, MessageDescriptor descriptor) {
        return MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key(message.getKey())
                .headers(message.getHeaders())
                .descriptor(descriptor)
                .createdAt(System.currentTimeMillis())
                .payload(message.getData())
                .build();
    }
}
