package com.roseboard.infrastructure.queue.config;

public enum SubmitStrategyType {
    BURST,
    BATCH,
    SEQUENTIAL_BY_ORIGINATOR,
    SEQUENTIAL_BY_TENANT,
    SEQUENTIAL
}
