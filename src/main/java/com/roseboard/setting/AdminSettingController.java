package com.roseboard.setting;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.setting.mfa.MfaSetting;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 系统设置接口：管理通用设置以及 MFA、安全和 JWT 强类型设置。
 */
@RestController
@RequestMapping("/api/admin-settings")
public class AdminSettingController {
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "refreshToken", "secret");

    private final AdminSettingService adminSettingService;

    public AdminSettingController(AdminSettingService adminSettingService) {
        this.adminSettingService = adminSettingService;
    }

    /**
     * 处理 /{key} 对应的接口请求。
     */


    @GetMapping("/{key}")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public AdminSettingEntity getAdminSettings(@PathVariable String key) {
        AdminSettingEntity settings = adminSettingService.findByKey(key);
        if (settings == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Setting not found: " + key);
        }
        return sanitize(settings);
    }

    /**
     * 处理 当前路径 对应的接口请求。
     */
    @PostMapping
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "#value.key")
    public AdminSettingEntity saveAdminSettings(@Valid @RequestBody AdminSettingEntity value) {
        return sanitize(adminSettingService.saveRaw(value.getKey(), value.getValue()));
    }

    /**
     * 查询 MFA 平台设置。
     */
    @GetMapping("/mfa")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public MfaSetting getMfaSettings() {
        return adminSettingService.getMfaSettings();
    }

    /**
     * 保存 MFA 平台设置。
     */
    @PostMapping("/mfa")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "mfa")
    public MfaSetting saveMfaSettings(@Valid @RequestBody MfaSetting value) {
        return adminSettingService.saveMfaSettings(value);
    }

    /**
     * 处理 /security 对应的接口请求。
     */
    @GetMapping("/security")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public SecuritySetting getSecuritySettings() {
        return adminSettingService.getSecuritySettings();
    }

    /**
     * 处理 /security 对应的接口请求。
     */
    @PostMapping("/security")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "security")
    public SecuritySetting saveSecuritySettings(@Valid @RequestBody SecuritySetting value) {
        return adminSettingService.saveSecuritySettings(value);
    }

    /**
     * 处理 /jwt 对应的接口请求。
     */
    @GetMapping("/jwt")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.READ)
    public JwtSetting getJwtSettings() {
        return adminSettingService.getJwtSettings();
    }

    /**
     * 处理 /jwt 对应的接口请求。
     */
    @PostMapping("/jwt")
    @RequirePermission(resource = EntityType.ADMIN_SETTINGS, operation = Operation.WRITE)
    @Audited(action = AuditActions.ADMIN_SETTINGS_UPDATED, entityType = EntityType.ADMIN_SETTINGS,
            entityName = "jwt")
    public JwtSetting saveJwtSettings(@Valid @RequestBody JwtSetting value) {
        return adminSettingService.saveJwtSettings(value);
    }

    private AdminSettingEntity sanitize(AdminSettingEntity source) {
        AdminSettingEntity copy = new AdminSettingEntity();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setCreatedTime(source.getCreatedTime());
        copy.setKey(source.getKey());
        if (source.getValue() instanceof ObjectNode object) {
            ObjectNode json = object.deepCopy();
            SENSITIVE_FIELDS.forEach(json::remove);
            copy.setValue(json);
        } else {
            copy.setValue(source.getValue());
        }
        return copy;
    }
}
