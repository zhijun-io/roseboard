package com.roseboard.tenant.usage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Web integration owned by the tenant usage module.
 */
@AutoConfiguration
public class TenantUsageAutoConfiguration {
    @Bean
    TenantRestRateLimitFilter tenantRestRateLimitFilter(TenantRateLimitService rateLimitService) {
        return new TenantRestRateLimitFilter(rateLimitService);
    }
}
