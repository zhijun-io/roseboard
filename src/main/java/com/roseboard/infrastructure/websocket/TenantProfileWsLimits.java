package com.roseboard.infrastructure.websocket;

import com.roseboard.tenant.usage.TenantUsageService;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TenantProfileWsLimits {
    public static final String MAX_WS_SESSIONS_PER_TENANT = "maxWsSessionsPerTenant";
    public static final String MAX_WS_SESSIONS_PER_CUSTOMER = "maxWsSessionsPerCustomer";
    public static final String MAX_WS_SESSIONS_PER_REGULAR_USER = "maxWsSessionsPerRegularUser";
    public static final String MAX_WS_SESSIONS_PER_PUBLIC_USER = "maxWsSessionsPerPublicUser";
    public static final String MAX_WS_SUBSCRIPTIONS_PER_TENANT = "maxWsSubscriptionsPerTenant";
    public static final String MAX_WS_SUBSCRIPTIONS_PER_CUSTOMER = "maxWsSubscriptionsPerCustomer";
    public static final String MAX_WS_SUBSCRIPTIONS_PER_REGULAR_USER = "maxWsSubscriptionsPerRegularUser";
    public static final String MAX_WS_SUBSCRIPTIONS_PER_PUBLIC_USER = "maxWsSubscriptionsPerPublicUser";
    public static final String WS_MSG_QUEUE_LIMIT_PER_SESSION = "wsMsgQueueLimitPerSession";
    public static final String WS_UPDATES_PER_SESSION_RATE_LIMIT = "wsUpdatesPerSessionRateLimit";
    public static final String TRANSPORT_TENANT_MSG_RATE_LIMIT = "transportTenantMsgRateLimit";
    public static final String TRANSPORT_DEVICE_MSG_RATE_LIMIT = "transportDeviceMsgRateLimit";
    public static final String TRANSPORT_TENANT_TELEMETRY_MSG_RATE_LIMIT = "transportTenantTelemetryMsgRateLimit";
    public static final String TRANSPORT_TENANT_TELEMETRY_DATA_POINTS_RATE_LIMIT =
            "transportTenantTelemetryDataPointsRateLimit";
    public static final String TRANSPORT_DEVICE_TELEMETRY_MSG_RATE_LIMIT = "transportDeviceTelemetryMsgRateLimit";
    public static final String TRANSPORT_DEVICE_TELEMETRY_DATA_POINTS_RATE_LIMIT =
            "transportDeviceTelemetryDataPointsRateLimit";

    private final TenantUsageService usageService;

    public TenantProfileWsLimits(TenantUsageService usageService) {
        this.usageService = usageService;
    }

    public int maxWsSessionsPerTenant(UUID tenantId) {
        return intLimit(tenantId, MAX_WS_SESSIONS_PER_TENANT);
    }

    public int maxWsSessionsPerCustomer(UUID tenantId) {
        return intLimit(tenantId, MAX_WS_SESSIONS_PER_CUSTOMER);
    }

    public int maxWsSessionsPerRegularUser(UUID tenantId) {
        return intLimit(tenantId, MAX_WS_SESSIONS_PER_REGULAR_USER);
    }

    public int maxWsSessionsPerPublicUser(UUID tenantId) {
        return intLimit(tenantId, MAX_WS_SESSIONS_PER_PUBLIC_USER);
    }

    public long maxWsSubscriptionsPerTenant(UUID tenantId) {
        return usageService.profileConfigurationLong(tenantId, MAX_WS_SUBSCRIPTIONS_PER_TENANT);
    }

    public long maxWsSubscriptionsPerCustomer(UUID tenantId) {
        return usageService.profileConfigurationLong(tenantId, MAX_WS_SUBSCRIPTIONS_PER_CUSTOMER);
    }

    public long maxWsSubscriptionsPerRegularUser(UUID tenantId) {
        return usageService.profileConfigurationLong(tenantId, MAX_WS_SUBSCRIPTIONS_PER_REGULAR_USER);
    }

    public long maxWsSubscriptionsPerPublicUser(UUID tenantId) {
        return usageService.profileConfigurationLong(tenantId, MAX_WS_SUBSCRIPTIONS_PER_PUBLIC_USER);
    }

    public int wsMsgQueueLimitPerSession(UUID tenantId) {
        return intLimit(tenantId, WS_MSG_QUEUE_LIMIT_PER_SESSION);
    }

    public String wsUpdatesPerSessionRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, WS_UPDATES_PER_SESSION_RATE_LIMIT);
    }
    public String transportTenantMsgRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_TENANT_MSG_RATE_LIMIT);
    }

    public String transportDeviceMsgRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_DEVICE_MSG_RATE_LIMIT);
    }

    public String transportTenantTelemetryMsgRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_TENANT_TELEMETRY_MSG_RATE_LIMIT);
    }

    public String transportTenantTelemetryDataPointsRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_TENANT_TELEMETRY_DATA_POINTS_RATE_LIMIT);
    }

    public String transportDeviceTelemetryMsgRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_DEVICE_TELEMETRY_MSG_RATE_LIMIT);
    }

    public String transportDeviceTelemetryDataPointsRateLimit(UUID tenantId) {
        return usageService.rateLimitConfiguration(tenantId, TRANSPORT_DEVICE_TELEMETRY_DATA_POINTS_RATE_LIMIT);
    }

    private int intLimit(UUID tenantId, String key) {
        return Math.toIntExact(usageService.profileConfigurationLong(tenantId, key));
    }
}
