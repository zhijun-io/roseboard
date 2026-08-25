package com.roseboard.infrastructure.message;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodecConcurrencyLimitsTest {

    @Test
    void concurrentEncodeDecodeIsDeterministic() throws Exception {
        CodecRegistry registry = CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, Sample.class)
                .build();

        int threads = 8;
        int perThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    Sample sample = new Sample(seed * 1000L + i, "celsius");
                    MessageEnvelope envelope = registry.encode(EncodeRequest.builder()
                            .messageId(UUID.randomUUID())
                            .key("k-" + seed)
                            .messageType("telemetry.post")
                            .createdAt(i)
                            .value(sample)
                            .build());
                     Sample decoded = registry.decode(envelope, Sample.class);
                    assertEquals(sample, decoded);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
    }

    @Test
    void rejectsOversizedPayloadAndExcessiveDepth() {
        CodecRegistry registry = CodecRegistry.builder()
                .registerJson("telemetry.post", "telemetry.v1", 1, Sample.class)
                .build();

        byte[] oversized = new byte[MessageLimits.MAX_PAYLOAD_BYTES + 1];
        assertThrows(MessageContractException.class, () ->
                MessageEnvelope.builder()
                        .messageId(UUID.randomUUID())
                        .key("k")
                        .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                        .createdAt(1L)
                        .payload(oversized)
                        .build());

        String deep = "{\"a\":".repeat(MessageLimits.MAX_JSON_DEPTH + 2) + "1"
                + "}".repeat(MessageLimits.MAX_JSON_DEPTH + 2);
        MessageEnvelope deepEnvelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key("k")
                .descriptor(MessageDescriptor.json("telemetry.post", "telemetry.v1", 1))
                .createdAt(1L)
                .payload(deep.getBytes(StandardCharsets.UTF_8))
                .build();
        MessageContractException exception = assertThrows(MessageContractException.class, () ->
                registry.decode(deepEnvelope, Sample.class));
        assertEquals(MessageErrorCode.SCHEMA_VALIDATION, exception.errorCode());
    }

    record Sample(long value, String unit) {
    }
}
