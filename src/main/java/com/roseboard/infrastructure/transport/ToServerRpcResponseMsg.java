package com.roseboard.infrastructure.transport;

import tools.jackson.databind.JsonNode;

public record ToServerRpcResponseMsg(int requestId, JsonNode payload, String error) {
}
