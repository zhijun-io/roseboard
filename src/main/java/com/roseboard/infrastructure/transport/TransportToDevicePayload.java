package com.roseboard.infrastructure.transport;

import tools.jackson.databind.JsonNode;

public record TransportToDevicePayload(String method, JsonNode params, Integer requestId) {
}
