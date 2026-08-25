package com.roseboard.tenant.usage;

import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 租户用量接口：查询指定租户当前周期的资源使用情况。
 */
@RestController
@RequestMapping("/api")
public class TenantUsageController {
    private final TenantUsageService usageService;
    private final DataScopeAuthorizer dataScopeService;

    public TenantUsageController(TenantUsageService usageService, DataScopeAuthorizer dataScopeService) {
        this.usageService = usageService;
        this.dataScopeService = dataScopeService;
    }


    /**
     * 查询指定租户用量。
     */


    @GetMapping("/tenants/{tenantId}/usage")
    @RequirePermission(resource = EntityType.USAGE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public TenantUsageReport byTenant(@PathVariable UUID tenantId, Authentication authentication) {
        dataScopeService.requireTenant(authentication, tenantId);
        return usageService.report(tenantId);
    }
}
