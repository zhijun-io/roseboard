package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ToServerRpcHandlers {
    private ToServerRpcHandlers() {
    }

    static JsonNode reply(ToServerRpcRequestMsg request) {
        if ("sumOnServer".equals(request.method())) {
            JsonNode params = request.params();
            ObjectNode result = JacksonUtils.newObjectNode();
            result.put("result", params.path("a").asInt() + params.path("b").asInt());
            return result;
        }
        return JacksonUtils.newObjectNode().put("success", true);
    }
}
