package com.roseboard.tenant.profile;

import com.roseboard.common.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TB {@code TenantProfileData} shape helpers — no new domain entity beyond JSON.
 */
final class TenantProfileData {
    private TenantProfileData() {
    }

    static JsonNode normalize(JsonNode raw) {
        ObjectNode root;
        if (raw == null || raw.isNull()) {
            root = JacksonUtils.objectMapper().createObjectNode();
        } else if (raw.isTextual()) {
            JsonNode parsed = JacksonUtils.toJsonNode(raw.asText());
            root = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy()
                    : JacksonUtils.objectMapper().createObjectNode();
        } else if (raw.isObject()) {
            root = (ObjectNode) raw.deepCopy();
        } else {
            root = JacksonUtils.objectMapper().createObjectNode();
        }
        if (!root.has("configuration") || root.get("configuration").isNull()
                || !root.get("configuration").isObject()) {
            root.set("configuration", JacksonUtils.objectMapper().createObjectNode());
        }
        if (!root.has("queueConfiguration") || root.get("queueConfiguration").isNull()
                || !root.get("queueConfiguration").isArray()) {
            ArrayNode queues = JacksonUtils.objectMapper().createArrayNode();
            root.set("queueConfiguration", queues);
        }
        return root;
    }
}
