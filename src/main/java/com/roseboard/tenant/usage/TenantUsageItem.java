package com.roseboard.tenant.usage;

public record TenantUsageItem(
        String key,
        long used,
        long limit,
        double warnThreshold,
        String status
) {
}
