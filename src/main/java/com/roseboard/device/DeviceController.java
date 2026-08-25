package com.roseboard.device;
import com.roseboard.infrastructure.audit.event.EntityType;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.JacksonUtils;
import com.roseboard.common.PageData;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialService;

/**
 * 设备资源接口：提供设备 CRUD、客户关系和设备凭据管理。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {
    private final DeviceService deviceService;
    private final DeviceCredentialService credentialsService;
    private final DataScopeAuthorizer dataScopeService;

    public DeviceController(DeviceService deviceService,
                            DeviceCredentialService credentialsService,
                            DataScopeAuthorizer dataScopeService) {
        this.deviceService = deviceService;
        this.credentialsService = credentialsService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 按设备 ID 查询设备。
     */
    @GetMapping("/devices/{deviceId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public DeviceEntity getDevice(@PathVariable UUID deviceId, Authentication authentication) {
        return deviceService.requireScoped(deviceId, authentication);
    }

    /**
     * 分页或批量查询设备。
     */
    @GetMapping(value = "/devices", params = "deviceIds")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public List<DeviceEntity> getDevices(@RequestParam UUID[] deviceIds, Authentication authentication) {
        return deviceService.findByIds(List.of(deviceIds)).stream()
                .filter(device -> deviceService.allowed(authentication, device))
                .toList();
    }

    /**
     * 分页或批量查询设备。
     */
    @GetMapping("/devices")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public PageData<DeviceEntity> getDevices(@RequestParam long pageSize, @RequestParam long page,
                                              @RequestParam(required = false) String textSearch,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) UUID deviceProfileId,
                                              Authentication authentication) {
        SecurityUsers scope = dataScopeService.current(authentication);
        if (scope.getCustomerId() != null) {
            return deviceService.findPageByCustomer(scope.getCustomerId(), pageSize, page, textSearch,
                    type, deviceProfileId);
        }
        return scope.getTenantId() == null
                ? deviceService.findPage(pageSize, page, textSearch, type, deviceProfileId)
                : deviceService.findPageByTenant(scope.getTenantId(), pageSize, page, textSearch,
                type, deviceProfileId);
    }

    /**
     * 将设备分配给客户。
     */
    @PutMapping("/devices/{deviceId}/customer")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_UPDATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public DeviceEntity assignDeviceToCustomer(@PathVariable UUID deviceId,
                                               @RequestBody CustomerAssignmentRequest request,
                                               Authentication authentication) {
        return deviceService.assignToCustomerScoped(deviceId, request.customerId(), authentication);
    }

    /**
     * 解除设备与客户的关联。
     */
    @DeleteMapping("/devices/{deviceId}/customer")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_UPDATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public DeviceEntity unassignDeviceFromCustomer(@PathVariable UUID deviceId,
                                                   Authentication authentication) {
        deviceService.requireScoped(deviceId, authentication);
        return deviceService.unassignFromCustomer(deviceId);
    }

    /**
     * 创建或更新设备。
     */
    @PostMapping("/devices")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = "#body.has('id') && !#body.get('id').isNull() ? T(com.roseboard.audit.AuditActions).DEVICE_UPDATED : T(com.roseboard.audit.AuditActions).DEVICE_CREATED",
            entityType = EntityType.DEVICE, entityId = "#result.id", entityName = "#result.name")
    public DeviceEntity saveDevice(@RequestBody JsonNode body, Authentication authentication) {
        DeviceEntity device = JacksonUtils.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(body, DeviceEntity.class);
        return deviceService.saveWithScope(device, authentication);
    }

    /**
     * 处理 /devices/{deviceId} 对应的接口请求。
     */
    @PutMapping("/devices/{deviceId}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_UPDATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId", entityName = "#result.name")
    public DeviceEntity updateDevice(@PathVariable UUID deviceId, @RequestBody JsonNode body,
                                     Authentication authentication) {
        DeviceEntity device = JacksonUtils.IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.convertValue(body, DeviceEntity.class);
        device.setId(deviceId);
        return deviceService.saveWithScope(device, authentication);
    }

    /**
     * 删除设备。
     */
    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.DELETE)
    @Audited(action = AuditActions.DEVICE_DELETED, entityType = EntityType.DEVICE, entityId = "#deviceId")
    public void deleteDevice(@PathVariable UUID deviceId, Authentication authentication) {
        deviceService.requireScoped(deviceId, authentication);
        deviceService.deleteById(deviceId);
    }

    /**
     * 查询设备凭据的脱敏信息。
     */
    @GetMapping("/devices/{deviceId}/credentials")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public DeviceCredentialEntity getCredentials(@PathVariable UUID deviceId, Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return credentialsService.requireMasked(device.getTenantId(), deviceId);
    }

    /**
     * 生成设备访问凭据。
     */
    @PostMapping("/devices/{deviceId}/credentials")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
            @Audited(action = AuditActions.DEVICE_CREDENTIALS_GENERATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public DeviceCredentialEntity generateCredentials(@PathVariable UUID deviceId,
                                                      Authentication authentication) {
        deviceService.requireScoped(deviceId, authentication);
        return credentialsService.generateAccessToken(deviceId);
    }

    /**
     * 撤销设备访问凭据。
     */
    @DeleteMapping("/devices/{deviceId}/credentials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
            @Audited(action = AuditActions.DEVICE_CREDENTIALS_REVOKED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public void revokeCredentials(@PathVariable UUID deviceId, Authentication authentication) {
        deviceService.requireScoped(deviceId, authentication);
        credentialsService.revoke(deviceId);
    }
    public record CustomerAssignmentRequest(UUID customerId) {
    }

}
