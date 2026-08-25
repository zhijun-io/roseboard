package com.roseboard.common.security;

import java.util.UUID;

/**
 * 统一的当前主体模型，供授权和审计共同使用。
 */
public interface SecurityUsers {
    SecurityUsers ANONYMOUS = new Value(null, null, null, null, false);

    UUID getUserId();

    UUID getTenantId();

    UUID getCustomerId();

    String getUserName();

    boolean isSystemAdmin();

    static SecurityUsers of(UUID userId, UUID tenantId, UUID customerId) {
        return new Value(userId, tenantId, customerId, null, false);
    }

    static SecurityUsers of(UUID userId, UUID tenantId, UUID customerId, String userName) {
        return of(userId, tenantId, customerId, userName, false);
    }

    static SecurityUsers of(UUID userId, UUID tenantId, UUID customerId,
                            String userName, boolean systemAdmin) {
        return new Value(userId, tenantId, customerId, userName, systemAdmin);
    }

    static SecurityUsers anonymous() {
        return ANONYMOUS;
    }

    record Value(
            UUID userId,
            UUID tenantId,
            UUID customerId,
            String userName,
            boolean systemAdmin) implements SecurityUsers {

        @Override
        public UUID getUserId() {
            return userId;
        }

        @Override
        public UUID getTenantId() {
            return tenantId;
        }

        @Override
        public UUID getCustomerId() {
            return customerId;
        }

        @Override
        public String getUserName() {
            return userName;
        }

        @Override
        public boolean isSystemAdmin() {
            return systemAdmin;
        }
    }
}
