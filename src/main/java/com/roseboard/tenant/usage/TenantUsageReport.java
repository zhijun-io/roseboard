package com.roseboard.tenant.usage;

import java.util.List;
import java.util.UUID;

public record TenantUsageReport(
        UUID tenantId,
        String period,
        List<TenantUsageItem> items
) {
}
