package com.roseboard.infrastructure.audit;

import com.roseboard.infrastructure.audit.event.AuditOrigin;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 与运行环境无关的审计上下文。Servlet、消息消费者和定时任务都通过这个小接口提供关联信息。
 */
public record AuditContext(AuditOrigin origin, String requestId, Map<String, String> attributes) {

    public static final int MAX_REQUEST_ID_LENGTH = 128;

    public AuditContext {
        origin = origin == null ? AuditOrigin.INTERNAL : origin;
        requestId = normalize(requestId);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AuditContext empty() {
        return new AuditContext(AuditOrigin.INTERNAL, null, Map.of());
    }

    public static AuditContext of(AuditOrigin origin, String requestId, Map<String, String> attributes) {
        return new AuditContext(origin, requestId, attributes);
    }

    public boolean isEmpty() {
        return origin == AuditOrigin.INTERNAL
                && requestId == null
                && attributes.isEmpty();
    }

    /** 仅序列化 {@link #attributes}；无属性时返回 {@code null}。 */
    public ObjectNode attributesNode() {
        if (attributes.isEmpty()) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        attributes.forEach(node::put);
        return node;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("Audit request id exceeds 128 characters");
        }
        return value;
    }
}
