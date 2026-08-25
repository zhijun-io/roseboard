package com.roseboard.infrastructure.message;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufCodecEquivalenceTest {

    @Test
    void jsonAndProtobufRoundTripProduceEquivalentDomainValues() {
        CodecRegistry registry = telemetryRegistry();
        TelemetrySample value = new TelemetrySample(21L, "celsius");
        Map<String, String> metadata = Map.of("tenantId", "t-1", "deviceId", "d-1");

        MessageEnvelope jsonEnvelope = registry.encode(EncodeRequest.builder()
                .messageId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .key("device-1")
                .messageType("telemetry.post")
                .contentType(ContentType.APPLICATION_JSON)
                .metadata(metadata)
                .createdAt(99L)
                .value(value)
                .build());

        MessageEnvelope protobufEnvelope = registry.encode(EncodeRequest.builder()
                .messageId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .key("device-1")
                .messageType("telemetry.post")
                .contentType(ContentType.APPLICATION_X_PROTOBUF)
                .metadata(metadata)
                .createdAt(99L)
                .value(value)
                .build());

         TelemetrySample fromJson = registry.decode(jsonEnvelope, TelemetrySample.class);
         TelemetrySample fromProtobuf = registry.decode(protobufEnvelope, TelemetrySample.class);

        assertEquals(value, fromJson);
        assertEquals(value, fromProtobuf);
        assertEquals(metadata, jsonEnvelope.metadata());
        assertEquals(metadata, protobufEnvelope.metadata());
        assertEquals(ContentType.APPLICATION_JSON, jsonEnvelope.descriptor().contentType());
        assertEquals(ContentType.APPLICATION_X_PROTOBUF, protobufEnvelope.descriptor().contentType());
    }

    @Test
    void preservesUnknownProtobufFieldsOnForwardEncode() throws Exception {
        Descriptors.Descriptor descriptor = TelemetryProtobufFixture.descriptor();
        byte[] original = TelemetryProtobufFixture.withUnknownField(7L, "celsius", 999, 42L);

        CodecRegistry registry = telemetryRegistry();
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(MessageDescriptor.protobuf("telemetry.post", "telemetry.v1", 1))
                .createdAt(1L)
                .payload(original)
                .build();

         TelemetrySample decoded = registry.decode(envelope, TelemetrySample.class);
        assertEquals(new TelemetrySample(7L, "celsius"), decoded);

        byte[] forwarded = TelemetryProtobufFixture.forwardPreserveUnknown(original);
        DynamicMessage parsed = DynamicMessage.parseFrom(descriptor, forwarded);
        assertTrue(parsed.getUnknownFields().hasField(999));
        assertEquals(42L, parsed.getUnknownFields().getField(999).getVarintList().getFirst());
        assertEquals(1, descriptor.findFieldByNumber(1).getNumber());
        assertEquals(2, descriptor.findFieldByNumber(2).getNumber());
    }

    @Test
    void rejectsJavaClassNameAndUnknownTypeUrlWithoutLoading() {
        AtomicBoolean loaded = new AtomicBoolean(false);
        CodecRegistry registry = telemetryRegistry();
        MessageEnvelope envelope = registry.encode(EncodeRequest.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .messageType("telemetry.post")
                .contentType(ContentType.APPLICATION_X_PROTOBUF)
                .createdAt(1L)
                .value(new TelemetrySample(1L, "celsius"))
                .build());

        MessageContractException className = assertThrows(MessageContractException.class, () -> {
            try {
                Class<?> foreign = Class.forName("java.util.concurrent.ConcurrentHashMap");
                loaded.set(true);
                registry.decode(envelope, foreign);
            } catch (ClassNotFoundException exception) {
                throw new AssertionError(exception);
            }
        });
        assertEquals(MessageErrorCode.SCHEMA_VALIDATION, className.errorCode());
        assertTrue(loaded.get());

        MessageContractException typeUrl = assertThrows(MessageContractException.class, () ->
                TelemetryProtobufFixture.rejectTypeUrl("type.googleapis.com/evil.Type"));
        assertEquals(MessageErrorCode.UNKNOWN_SCHEMA, typeUrl.errorCode());
        assertFalse(TelemetryProtobufFixture.wasReflectiveLoadAttempted());
    }

    private static CodecRegistry telemetryRegistry() {
        return CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, TelemetrySample.class)
                .registerProtobuf("telemetry.post", "telemetry.v1", 1, TelemetrySample.class,
                        TelemetryProtobufFixture.CODEC)
                .build();
    }

    record TelemetrySample(long value, String unit) {
    }
}
