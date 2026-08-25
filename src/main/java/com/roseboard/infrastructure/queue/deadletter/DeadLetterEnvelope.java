package com.roseboard.infrastructure.queue.deadletter;

import java.util.Map;

public record DeadLetterEnvelope(
        String key,
        String dataBase64,
        Map<String, String> headersBase64,
        String sourceTopic,
        Integer sourcePartition,
        String failureType,
        String failureMessage) {
}
