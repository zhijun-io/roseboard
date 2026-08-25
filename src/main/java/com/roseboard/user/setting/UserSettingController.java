package com.roseboard.user.setting;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.setting.security.DataScopeAuthorizer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import static com.roseboard.user.setting.UserSettingService.GENERAL;

/**
 * 当前用户设置接口：读取、更新和删除当前用户的设置。
 */
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/users/me/settings")
public class UserSettingController {
    private final UserSettingService userSettingService;
    private final DataScopeAuthorizer dataScopeService;

    public UserSettingController(UserSettingService userSettingService, DataScopeAuthorizer dataScopeService) {
        this.userSettingService = userSettingService;
        this.dataScopeService = dataScopeService;
    }

    /**
     * 查询单个资源。
     */


    @GetMapping("/general")
    public JsonNode get(Authentication authentication) {
        return userSettingService.get(dataScopeService.requireUserId(authentication), GENERAL);
    }

    /**
     * 处理 /general 对应的接口请求。
     */
    @PutMapping("/general")
    @Audited(action = AuditActions.USER_SETTINGS_UPDATED, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public void updateGeneral(@RequestBody JsonNode settings, Authentication authentication) {
        userSettingService.save(dataScopeService.requireUserId(authentication), GENERAL,
                JacksonUtils.writeValueAsString(settings));
    }


    /**
     * 处理 /general 对应的接口请求。
     */
    @DeleteMapping("/general")
    @Audited(action = AuditActions.USER_SETTINGS_DELETED, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public void deleteGeneral(Authentication authentication) {
        userSettingService.delete(dataScopeService.requireUserId(authentication), GENERAL);
    }
}
