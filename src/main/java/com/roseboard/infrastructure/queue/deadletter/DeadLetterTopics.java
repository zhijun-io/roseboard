package com.roseboard.infrastructure.queue.deadletter;

public final class DeadLetterTopics {

    private DeadLetterTopics() {
    }

    public static String resolve(String queueTopic) {
        if (queueTopic == null || queueTopic.isBlank()) {
            throw new IllegalArgumentException("queueTopic must be non-empty");
        }
        return queueTopic + ".dlq";
    }
}
