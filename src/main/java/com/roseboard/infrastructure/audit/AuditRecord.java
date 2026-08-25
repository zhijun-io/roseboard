package com.roseboard.infrastructure.audit;

import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.writer.AuditWriter;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已产生、可持久化的审计事件。领域层构造完成后交给 {@link AuditWriter}。
 */
public record AuditRecord(UUID id, Instant occurredAt, String action, SecurityUsers principal,
                          AuditTarget target, AuditStatus status, JsonNode detail,
                          String failureMessage, AuditContext context,
                          Long durationMs) {

    private static final int MAX_FAILURE_MESSAGE_CHARS = 2_048;

    public static AuditRecord create(String action, SecurityUsers principal, AuditTarget target,
                                     AuditStatus status, JsonNode detail, String failureMessage,
                                     AuditContext context, Long durationMs) {
        SecurityUsers safePrincipal = principal == null ? SecurityUsers.anonymous() : principal;
        AuditContext safeContext = context == null ? AuditContext.empty() : context;
        Objects.requireNonNull(target, "Audit target must not be null");
        if (status == null) {
            throw new IllegalArgumentException("Audit status must not be null");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Audit durationMs must not be negative");
        }
        validatePayloadSize(detail, safeContext);
        failureMessage = status == AuditStatus.FAILED
                ? StringUtils.abbreviate(failureMessage, MAX_FAILURE_MESSAGE_CHARS) : null;
        return new AuditRecord(UUID.randomUUID(), Instant.now(), validateAction(action), safePrincipal, target,
                status, detail, failureMessage, safeContext, durationMs);
    }

    private static void validatePayloadSize(JsonNode detail, AuditContext context) {
        if (utf8Size(detail) > 16 * 1024) {
            throw new AuditPayloadTooLargeException("Audit detail exceeds 16 KiB");
        }
        ObjectNode contextNode = context.attributesNode();
        if (contextNode != null && utf8Size(contextNode) > 4 * 1024) {
            throw new AuditPayloadTooLargeException("Audit context exceeds 4 KiB");
        }
    }

    private static int utf8Size(JsonNode node) {
        return node == null ? 0 : node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    public static final class AuditPayloadTooLargeException extends IllegalArgumentException {
        public AuditPayloadTooLargeException(String message) {
            super(message);
        }
    }

    private static String validateAction(String action) {
        if (action == null || action.isBlank() || action.length() > 100) {
            throw new IllegalArgumentException("Invalid audit action: " + action);
        }
        return action;
    }
}
