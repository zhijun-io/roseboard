package com.roseboard.tenant.usage;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.spi.NotificationUsageMeter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Host adapter that keeps tenant quota accounting outside the notification starter. */
@Component
public final class TenantNotificationUsageMeter implements NotificationUsageMeter {
    private final TenantUsageService usageService;

    public TenantNotificationUsageMeter(TenantUsageService usageService) {
        this.usageService = usageService;
    }

    @Override
    public void record(UUID tenantId, ChannelKind channel) {
        switch (channel) {
            case EMAIL -> usageService.recordEmail(tenantId);
            case SMS -> usageService.recordSms(tenantId);
            case WEB -> {
                // WEB delivery is an inbox write and has no quota counter.
            }
        }
    }
}
