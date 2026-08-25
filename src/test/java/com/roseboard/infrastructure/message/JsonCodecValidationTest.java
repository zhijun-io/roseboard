package com.roseboard.infrastructure.message;

import com.roseboard.infrastructure.message.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCodecValidationTest {

    private final CodecRegistry registry = CodecRegistry.builder()
            .registerJson("command.write", "command.v1", 1, WriteCommand.class)
            .build();

    @Test
    void rejectsDuplicateKeysNonFiniteNumbersAndInvalidFields() {
        assertValidationError("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"id\":\"22222222-2222-2222-2222-222222222222\",\"ts\":1,\"amount\":1}");
        assertValidationError("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"ts\":1,\"amount\":NaN}");
        assertValidationError("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"ts\":1,\"amount\":Infinity}");
        assertValidationError("{\"id\":\"not-a-uuid\",\"ts\":1,\"amount\":1}");
        assertValidationError("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"ts\":\"yesterday\",\"amount\":1}");
    }

    @Test
    void rejectsOversizedCollectionsBeforeHandler() {
        StringBuilder json = new StringBuilder("{\"id\":\"11111111-1111-1111-1111-111111111111\",\"ts\":1,\"amount\":1,\"tags\":[");
        for (int i = 0; i < MessageLimits.MAX_COLLECTION_SIZE + 1; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"t").append(i).append('"');
        }
        json.append("]}");
        assertValidationError(json.toString());
    }

    private void assertValidationError(String json) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(MessageDescriptor.json("command.write", "command.v1", 1))
                .createdAt(1L)
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .build();
        MessageContractException exception = assertThrows(MessageContractException.class, () ->
                registry.decode(envelope, WriteCommand.class));
        assertEquals(MessageErrorCode.SCHEMA_VALIDATION, exception.errorCode());
    }

    record WriteCommand(UUID id, long ts, long amount, java.util.List<String> tags) {
    }
}
