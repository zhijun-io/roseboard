package com.roseboard.tenant.usage;

import java.util.UUID;

public record TenantUsageThresholdEvent(
        UUID tenantId,
        String period,
        String metricKey,
        String status,
        long used,
        long limit,
        double warnThreshold
) {
}
