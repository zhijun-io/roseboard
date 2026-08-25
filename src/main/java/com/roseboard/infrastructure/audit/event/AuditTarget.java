package com.roseboard.infrastructure.audit.event;

import com.roseboard.common.security.SecurityUsers;

import java.util.Objects;
import java.util.UUID;

/**
 * Audit target: the entity affected by an action.
 */
public record AuditTarget(EntityType entityType, UUID entityId, String entityName,
                          UUID tenantId, UUID customerId) {
    public AuditTarget(EntityType entityType, UUID entityId, String entityName) {
        this(entityType, entityId, entityName, null, null);
    }

    public AuditTarget {
        Objects.requireNonNull(entityType, "entityType");
        if (entityName != null && entityName.isBlank()) {
            entityName = null;
        }
    }

    public static AuditTarget loginAttempt() {
        return of(EntityType.USER, null);
    }

    public static AuditTarget loginAttempt(String attemptedName) {
        return of(EntityType.USER, null, attemptedName);
    }

    public static AuditTarget loginAttempt(String attemptedName, UUID tenantId, UUID customerId) {
        return new AuditTarget(EntityType.USER, null, attemptedName, tenantId, customerId);
    }

    public static AuditTarget of(EntityType entityType, UUID entityId) {
        return new AuditTarget(entityType, entityId, null);
    }

    public static AuditTarget of(EntityType entityType, UUID entityId, String entityName) {
        return new AuditTarget(entityType, entityId, entityName);
    }

    public static AuditTarget user(UUID userId) {
        return new AuditTarget(EntityType.USER, userId, null);
    }

    public static AuditTarget user(SecurityUsers principal) {
        return user(principal.getUserId(), principal.getTenantId(), principal.getCustomerId());
    }

    public static AuditTarget user(UUID userId, UUID tenantId, UUID customerId) {
        return new AuditTarget(EntityType.USER, userId, null, tenantId, customerId);
    }

    public static AuditTarget device(UUID deviceId, UUID tenantId) {
        return new AuditTarget(EntityType.DEVICE, deviceId, null, tenantId, null);
    }
}
