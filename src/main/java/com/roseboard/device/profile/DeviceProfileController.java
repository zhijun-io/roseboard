package com.roseboard.device.profile;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.ota.OtaPackageServiceImpl;
import com.roseboard.ota.OtaPackageType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 设备画像接口：提供设备画像查询、保存、删除和默认画像切换。
 */
@RestController
@RequestMapping("/api/device-profiles")
public class DeviceProfileController {
    private final DeviceProfileService service;
    private final DataScopeAuthorizer dataScopeService;
    private final OtaPackageServiceImpl otaPackages;

    public DeviceProfileController(DeviceProfileService service, DataScopeAuthorizer dataScopeService,
                                   OtaPackageServiceImpl otaPackages) {
        this.service = service;
        this.dataScopeService = dataScopeService;
        this.otaPackages = otaPackages;
    }

    /**
     * 分页查询资源。
     */
    @GetMapping
    @RequirePermission(resource = EntityType.DEVICE_PROFILE, operation = Operation.READ)
    public PageData<DeviceProfileEntity> list(@RequestParam long pageSize, @RequestParam long page,
                                               Authentication authentication) {
        return service.findPage(dataScopeService.requireTenantId(authentication), pageSize, page);
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{profileId}")
    @RequirePermission(resource = EntityType.DEVICE_PROFILE, operation = Operation.READ)
    public DeviceProfileEntity get(@PathVariable UUID profileId, Authentication authentication) {
        return service.requireForTenant(dataScopeService.requireTenantId(authentication), profileId);
    }

    /**
     * 创建或更新资源。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.DEVICE_PROFILE, operation = Operation.WRITE)
    @Audited(action = "#body.has('id') && !#body.get('id').isNull() ? T(com.roseboard.audit.AuditActions).DEVICE_PROFILE_UPDATED : T(com.roseboard.audit.AuditActions).DEVICE_PROFILE_CREATED",
            entityType = EntityType.DEVICE_PROFILE, entityId = "#result.id", entityName = "#result.name")
    public DeviceProfileEntity save(@RequestBody JsonNode body,
                                    @RequestParam(required = false) Long expectedVersion,
                                    Authentication authentication) {
        DeviceProfileEntity profile = JacksonUtils.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER
                .convertValue(body, DeviceProfileEntity.class);
        UUID tenantId = dataScopeService.requireTenantId(authentication);
        otaPackages.requireAssignable(tenantId, profile.getFirmwareId(), OtaPackageType.FIRMWARE);
        otaPackages.requireAssignable(tenantId, profile.getSoftwareId(), OtaPackageType.SOFTWARE);
        return service.saveForTenant(tenantId, profile, expectedVersion == null ? 0L : expectedVersion);
    }

    /**
     * 切换默认资源。
     */
    @PutMapping("/{profileId}/default")
    @RequirePermission(resource = EntityType.DEVICE_PROFILE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEFAULT_PROFILE_SWITCHED, entityType = EntityType.DEVICE_PROFILE,
            entityId = "#profileId")
    public void switchDefault(@PathVariable UUID profileId, Authentication authentication) {
        service.switchDefault(dataScopeService.requireTenantId(authentication), profileId);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{profileId}")
    @RequirePermission(resource = EntityType.DEVICE_PROFILE, operation = Operation.DELETE)
    @Audited(action = AuditActions.DEVICE_PROFILE_DELETED, entityType = EntityType.DEVICE_PROFILE,
            entityId = "#profileId")
    public void delete(@PathVariable UUID profileId, Authentication authentication) {
        service.delete(dataScopeService.requireTenantId(authentication), profileId);
    }
}
