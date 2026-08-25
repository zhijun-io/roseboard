package com.roseboard.infrastructure.transport;

import tools.jackson.databind.JsonNode;

public record ToServerRpcRequestMsg(int requestId, String method, JsonNode params) {
}
