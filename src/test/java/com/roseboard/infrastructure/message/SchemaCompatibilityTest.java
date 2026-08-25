package com.roseboard.infrastructure.message;

import com.roseboard.infrastructure.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaCompatibilityTest {

    @Test
    void supportedOlderVersionFixtureRemainsReadable() {
        CodecRegistry registry = CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, TelemetryV1.class)
                .registerJson("telemetry.post", "telemetry.v1", 2, TelemetryV2.class)
                .build();

        MessageEnvelope v1 = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(new MessageDescriptor(
                        "telemetry.post",
                        ContentType.APPLICATION_JSON,
                        "telemetry.v1",
                        1,
                        MessageLimits.MAX_PAYLOAD_BYTES))
                .createdAt(1L)
                .payload("{\"value\":3,\"unit\":\"celsius\"}".getBytes(StandardCharsets.UTF_8))
                .build();

         TelemetryV1 decoded = registry.decode(v1, TelemetryV1.class);
        assertEquals(new TelemetryV1(3L, "celsius"), decoded);
    }

    @Test
    void breakingChangeCannotMasqueradeAsOldVersion() {
        CodecRegistry registry = CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, TelemetryV1.class)
                .build();

        MessageEnvelope withUnknownField = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(1L)
                .payload("{\"value\":3,\"unit\":\"celsius\",\"scale\":\"kelvin\"}".getBytes(StandardCharsets.UTF_8))
                .build();
         TelemetryV1 withUnknown = registry.decode(withUnknownField, TelemetryV1.class);
        assertEquals(3L, withUnknown.value());

        MessageEnvelope renamedBreaking = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(1L)
                .payload("{\"measurement\":3,\"unit\":\"celsius\"}".getBytes(StandardCharsets.UTF_8))
                .build();
        MessageContractException breaking = assertThrows(MessageContractException.class, () ->
                registry.decode(renamedBreaking, TelemetryV1.class));
        assertEquals(MessageErrorCode.SCHEMA_VALIDATION, breaking.errorCode());

        MessageContractException unknownVersion = assertThrows(MessageContractException.class, () ->
                registry.decode(MessageEnvelope.builder()
                        .messageId(UUID.randomUUID())
                        .key("k")
                        .descriptor(new MessageDescriptor(
                                "telemetry.post",
                                ContentType.APPLICATION_JSON,
                                "telemetry.v1",
                                99,
                                MessageLimits.MAX_PAYLOAD_BYTES))
                        .createdAt(1L)
                        .payload("{}".getBytes(StandardCharsets.UTF_8))
                        .build(), TelemetryV1.class));
        assertEquals(MessageErrorCode.UNKNOWN_SCHEMA, unknownVersion.errorCode());
    }

    record TelemetryV1(long value, String unit) {
    }

    record TelemetryV2(long value, String unit, String scale) {
    }
}
