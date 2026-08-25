package com.roseboard.tenant;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.common.security.SecurityUsers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 租户资源接口：提供租户 CRUD、分页和批量查询。
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final DataScopeAuthorizer dataScopeService;

    public TenantController(TenantService tenantService, DataScopeAuthorizer dataScopeService) {
        this.tenantService = tenantService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 按租户 ID 查询租户。
     */
    @GetMapping("/{tenantId}")
    @RequirePermission(resource = EntityType.TENANT, operation = Operation.READ)
    public TenantEntity getTenantById(@PathVariable UUID tenantId, Authentication authentication) {
        dataScopeService.requireTenant(authentication, tenantId);
        return tenantService.requireById(tenantId);
    }

    /**
     * 处理 tenantIds 对应的接口请求。
     */
    @GetMapping(params = "tenantIds")
    @RequirePermission(resource = EntityType.TENANT, operation = Operation.READ)
    public List<TenantEntity> getTenantsByIds(@RequestParam UUID[] tenantIds,
                                               Authentication authentication) {
        dataScopeService.requireSystemAdmin(authentication);
        return tenantService.findByIds(List.of(tenantIds));
    }

    /**
     * 创建或更新租户。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.TENANT, operation = Operation.WRITE)
    @Audited(action = "#payload.has('id') ? T(com.roseboard.audit.AuditActions).TENANT_UPDATED : T(com.roseboard.audit.AuditActions).TENANT_CREATED",
            entityType = EntityType.TENANT, entityId = "#result.id", entityName = "#result.title")
    public TenantEntity saveTenant(@RequestBody JsonNode payload, Authentication authentication) {
        if (!(payload instanceof ObjectNode object)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant payload must be an object");
        }
        JacksonUtils.putNestedId(object, "tenantProfileId", "tenantProfile");
        TenantEntity tenant = JacksonUtils.objectMapper().treeToValue(object, TenantEntity.class);
        if (tenant.getId() == null) {
            dataScopeService.requireSystemAdmin(authentication);
        } else {
            dataScopeService.requireTenant(authentication, tenant.getId());
        }
        return tenantService.save(tenant);
    }

    /**
     * 删除租户。
     */
    @DeleteMapping("/{tenantId}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.TENANT, operation = Operation.DELETE)
    @Audited(action = AuditActions.TENANT_DELETED, entityType = EntityType.TENANT,
            entityId = "#tenantId")
    public void deleteTenant(@PathVariable UUID tenantId, Authentication authentication) {
        dataScopeService.requireTenant(authentication, tenantId);
        tenantService.requireById(tenantId);
        tenantService.deleteById(tenantId);
    }

    /**
     * 分页查询
     */
    @GetMapping(params = {"pageSize", "page"})
    @RequirePermission(resource = EntityType.TENANT, operation = Operation.READ)
    public PageData<TenantEntity> getTenants(
            @RequestParam long pageSize,
            @RequestParam long page,
            @RequestParam(required = false) String textSearch,
            Authentication authentication) {
        SecurityUsers scope = dataScopeService.current(authentication);
        return scope.getTenantId() == null
                ? tenantService.findPage(pageSize, page, textSearch)
                : tenantService.findPageById(scope.getTenantId(), pageSize, page, textSearch);
    }
}
