package com.roseboard.tenant.profile;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 租户画像接口：提供画像 CRUD、摘要查询和默认画像切换。
 */
@RestController
@RequestMapping("/api/tenant-profiles")
public class TenantProfileController {
    private final TenantProfileService service;

    public TenantProfileController(TenantProfileService service) {
        this.service = service;
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{tenantProfileId}")
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public TenantProfileEntity get(@PathVariable UUID tenantProfileId) {
        return service.requireById(tenantProfileId);
    }

    /**
     * 查询指定租户画像摘要。
     */
    @GetMapping("/{tenantProfileId}/summary")
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public JsonNode info(@PathVariable UUID tenantProfileId) {
        ObjectNode result = JacksonUtils.convertValue(service.requireById(tenantProfileId), ObjectNode.class);
        result.remove("profileData");
        result.remove("isDefault");
        return result;
    }

    /**
     * 查询默认租户画像摘要。
     */
    @GetMapping("/default/summary")
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public JsonNode defaultInfo() {
        ObjectNode result = JacksonUtils.convertValue(service.requireDefault(), ObjectNode.class);
        result.remove("profileData");
        result.remove("isDefault");
        return result;
    }

    /**
     * 创建或更新资源。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.WRITE)
    @Audited(action = "#payload.has('id') ? T(com.roseboard.audit.AuditActions).TENANT_PROFILE_UPDATED : T(com.roseboard.audit.AuditActions).TENANT_PROFILE_CREATED",
            entityType = EntityType.TENANT_PROFILE, entityId = "#result.id")
    public TenantProfileEntity save(@RequestBody JsonNode payload) {
        if (!(payload instanceof ObjectNode object)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant profile payload must be an object");
        }
        return service.save(JacksonUtils.objectMapper().treeToValue(object, TenantProfileEntity.class));
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{tenantProfileId}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.DELETE)
    @Audited(action = AuditActions.TENANT_PROFILE_DELETED, entityType = EntityType.TENANT_PROFILE,
            entityId = "#tenantProfileId")
    public void delete(@PathVariable UUID tenantProfileId) {
        service.delete(tenantProfileId);
    }

    /**
     * 设置默认资源。
     */
    @PutMapping("/{tenantProfileId}/default")
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.WRITE)
    @Audited(action = AuditActions.TENANT_PROFILE_DEFAULT_SWITCHED, entityType = EntityType.TENANT_PROFILE,
            entityId = "#tenantProfileId")
    public TenantProfileEntity setDefault(@PathVariable UUID tenantProfileId) {
        return service.setDefault(tenantProfileId);
    }

    /**
     * 分页查询租户画像摘要。
     */
    @GetMapping(value = "/summaries", params = {"pageSize", "page"})
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public PageData<TenantProfileEntity> summaryPage(@RequestParam long pageSize, @RequestParam long page,
                                                     @RequestParam(required = false) String textSearch) {
        return service.findPage(pageSize, page, textSearch);
    }

    /**
     * 按 ID 批量查询资源。
     */
    @GetMapping(params = "ids")
    @RequirePermission(resource = EntityType.TENANT_PROFILE, operation = com.roseboard.infrastructure.security.api.Operation.READ)
    public List<TenantProfileEntity> listByIds(@RequestParam UUID[] ids) {
        return service.findByIds(Arrays.asList(ids));
    }
}
