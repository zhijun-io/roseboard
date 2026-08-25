package com.roseboard.device.attribute;

import com.roseboard.audit.AuditActions;
import jakarta.validation.constraints.NotBlank;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceService;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 设备属性接口：提供属性读取、批量写入和删除。
 */
@RestController
@RequestMapping("/api/devices")
@Validated
public class DeviceAttributeController {

    private final DeviceService deviceService;
    private final DeviceAttributeService attributeService;

    public DeviceAttributeController(DeviceService deviceService,
                                     DeviceAttributeService attributeService) {
        this.deviceService = deviceService;
        this.attributeService = attributeService;
    }

    /**
     * 查询单个资源。
     */
    @GetMapping("/{deviceId}/attributes/{key}")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public DeviceAttribute get(@PathVariable UUID deviceId,
                               @RequestParam AttributeScope scope,
                               @PathVariable String key,
                               Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return attributeService.read(authentication, device.getTenantId(), deviceId,
                scope, new AttributeKey(key));
    }

    /**
     * 分页查询资源。
     */
    @GetMapping("/{deviceId}/attributes")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public List<DeviceAttribute> list(@PathVariable UUID deviceId,
                                      @RequestParam AttributeScope scope,
                                      @RequestParam(required = false) String keys,
                                      Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        List<String> keyList = csv(keys);
        if (keyList.isEmpty()) {
            return attributeService.readAll(authentication, device.getTenantId(), deviceId, scope);
        }
        List<AttributeKey> attributeKeys = keyList.stream().map(AttributeKey::new).toList();
        return attributeService.read(authentication, device.getTenantId(), deviceId, scope, attributeKeys);
    }

    /**
     * 查询设备属性键列表。
     */
    @GetMapping("/{deviceId}/attributes/keys")
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.READ)
    public List<String> keys(@PathVariable UUID deviceId,
                             @RequestParam AttributeScope scope,
                             Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        return attributeService.keys(authentication, device.getTenantId(), deviceId, scope);
    }

    /**
     * 批量写入设备属性。
     */
    @PostMapping("/{deviceId}/attributes")
    @PreAuthorize("isAuthenticated()")
    @Audited(action = AuditActions.DEVICE_ATTRIBUTES_UPDATED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public AttributeBatchResult writeBatch(@PathVariable UUID deviceId,
                                            @Valid @RequestBody AttributeBatchRequest request,
                                            Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication, true);
        List<AttributeWrite> writes = request.items().stream()
                .map(item -> new AttributeWrite(item.scope(), new AttributeKey(item.key()),
                        new AttributeValue(JacksonUtils.convertValue(item.value(), Object.class)),
                        item.expectedVersion()))
                .toList();
        return attributeService.writeBatch(authentication, device.getTenantId(), deviceId,
                writes, request.mode());
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/{deviceId}/attributes")
    @ResponseStatus(HttpStatus.OK)
    @RequirePermission(resource = EntityType.DEVICE, operation = Operation.WRITE)
    @Audited(action = AuditActions.DEVICE_ATTRIBUTES_DELETED, entityType = EntityType.DEVICE,
            entityId = "#deviceId")
    public void delete(@PathVariable UUID deviceId,
                       @RequestParam AttributeScope scope,
                       @RequestParam @NotBlank String keys,
                       @RequestParam(required = false) Long expectedVersion,
                       Authentication authentication) {
        DeviceEntity device = deviceService.requireScoped(deviceId, authentication);
        List<String> keyList = csv(keys);
        for (String key : keyList) {
            AttributeKey attributeKey = new AttributeKey(key);
            long version = expectedVersion != null
                    ? expectedVersion
                    : attributeService.requireVersion(device.getTenantId(), deviceId, scope, attributeKey);
            attributeService.delete(device.getTenantId(), deviceId, scope, attributeKey, version);
        }
    }

    private static List<String> csv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(StringUtils.tokenizeToStringArray(value, ","));
    }
}
