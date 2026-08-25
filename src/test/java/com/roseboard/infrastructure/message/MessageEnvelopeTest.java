package com.roseboard.infrastructure.message;

import com.roseboard.infrastructure.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageEnvelopeTest {

    @Test
    void acceptsValidEnvelopeAndKeepsRoutingSeparateFromPayload() {
        UUID messageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        byte[] payload = "{\"temp\":21}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> headers = Map.of("x-trace", new byte[] {1, 2, 3});
        Map<String, String> metadata = Map.of(
                "tenantId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "deviceId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(messageId)
                .key("device-1")
                .headers(headers)
                .metadata(metadata)
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(1_700_000_000_000L)
                .payload(payload)
                .build();

        assertEquals(messageId, envelope.messageId());
        assertEquals("device-1", envelope.key());
        assertEquals("telemetry.post", envelope.descriptor().messageType());
        assertEquals(ContentType.APPLICATION_JSON, envelope.descriptor().contentType());
        assertEquals("telemetry.v1", envelope.descriptor().schemaId());
        assertEquals(1, envelope.descriptor().schemaVersion());
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", envelope.metadata().get("tenantId"));
        assertArrayEquals(payload, envelope.payload());
        assertArrayEquals(new byte[] {1, 2, 3}, envelope.headers().get("x-trace"));
    }

    @Test
    void toBuilderRoundTripPreservesPayloadAndBinaryHeaders() {
        byte[] payload = new byte[] {0x00, (byte) 0xff, 0x10, 0x20};
        Map<String, byte[]> headers = new LinkedHashMap<>();
        headers.put("x-unknown", new byte[] {9, 8, 7});
        headers.put("x-bin", new byte[] {0, 1, 2, 3, 4});

        MessageEnvelope original = MessageEnvelope.builder()
                .messageId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .key("device-1")
                .headers(headers)
                .metadata(Map.of("tenantId", "t1"))
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(42L)
                .payload(payload)
                .build();

        MessageEnvelope rebuilt = original.toBuilder().build();

        assertNotSame(original, rebuilt);
        assertArrayEquals(payload, rebuilt.payload());
        assertArrayEquals(new byte[] {9, 8, 7}, rebuilt.headers().get("x-unknown"));
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, rebuilt.headers().get("x-bin"));
        assertEquals(original.metadata(), rebuilt.metadata());
        assertEquals(original.descriptor(), rebuilt.descriptor());
        assertEquals(original.messageId(), rebuilt.messageId());
        assertEquals(original.key(), rebuilt.key());
        assertEquals(original.createdAt(), rebuilt.createdAt());
    }

    @Test
    void rejectsMissingMessageIdBeforeDecode() {
        MessageContractException exception = assertThrows(MessageContractException.class, () ->
                MessageEnvelope.builder()
                        .messageId(null)
                        .key("k")
                        .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                        .createdAt(1L)
                        .payload(new byte[] {1})
                        .build());
        assertEquals(MessageErrorCode.INVALID_ENVELOPE, exception.errorCode());
    }

    @Test
    void rejectsOversizedPayloadBeforeDecode() {
        byte[] payload = new byte[MessageLimits.MAX_PAYLOAD_BYTES + 1];
        MessageContractException exception = assertThrows(MessageContractException.class, () ->
                MessageEnvelope.builder()
                        .messageId(UUID.randomUUID())
                        .key("k")
                        .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                        .createdAt(1L)
                        .payload(payload)
                        .build());
        assertEquals(MessageErrorCode.PAYLOAD_TOO_LARGE, exception.errorCode());
    }

    @Test
    void rejectsOversizedHeadersAndControlCharacters() {
        Map<String, byte[]> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < MessageLimits.MAX_HEADER_ENTRIES + 1; i++) {
            tooMany.put("h" + i, new byte[] {1});
        }
        MessageContractException tooManyHeaders = assertThrows(MessageContractException.class, () ->
                validBuilder().headers(tooMany).build());
        assertEquals(MessageErrorCode.HEADERS_TOO_LARGE, tooManyHeaders.errorCode());

        MessageContractException badKey = assertThrows(MessageContractException.class, () ->
                validBuilder().headers(Map.of("bad\u0000key", new byte[] {1})).build());
        assertEquals(MessageErrorCode.INVALID_ENVELOPE, badKey.errorCode());
    }

    @Test
    void rejectsInvalidMetadataAndKey() {
        MessageContractException blankKey = assertThrows(MessageContractException.class, () ->
                validBuilder().key("  ").build());
        assertEquals(MessageErrorCode.INVALID_ENVELOPE, blankKey.errorCode());

        Map<String, String> metadata = Map.of("tenantId", "x".repeat(MessageLimits.MAX_METADATA_VALUE_CHARS + 1));
        MessageContractException oversizedMeta = assertThrows(MessageContractException.class, () ->
                validBuilder().metadata(metadata).build());
        assertEquals(MessageErrorCode.METADATA_TOO_LARGE, oversizedMeta.errorCode());
    }

    @Test
    void rejectsInvalidDescriptorFields() {
        MessageContractException badType = assertThrows(MessageContractException.class, () ->
                MessageDescriptor.json("", "telemetry.v1", 1));
        assertEquals(MessageErrorCode.INVALID_DESCRIPTOR, badType.errorCode());

        MessageContractException badVersion = assertThrows(MessageContractException.class, () ->
                MessageDescriptor.json("telemetry.post", "telemetry.v1", 0));
        assertEquals(MessageErrorCode.INVALID_DESCRIPTOR, badVersion.errorCode());
        assertTrue(badVersion.getMessage().contains("schemaVersion"));
    }

    private static MessageEnvelope.Builder validBuilder() {
        return MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("device-1")
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(1L)
                .payload(new byte[] {1});
    }
}
