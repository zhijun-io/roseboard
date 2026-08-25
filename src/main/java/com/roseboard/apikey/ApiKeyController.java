package com.roseboard.apikey;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API Key 管理接口：创建、查询、更新和删除用户 API Key。
 */
@RestController
@RequestMapping("/api")
public class ApiKeyController {
    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 分页查询资源。
     */
    @GetMapping("/users/{userId}/api-keys")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public PageData<ApiKeyEntity> list(@PathVariable UUID userId,
                                       @RequestParam long pageSize,
                                       @RequestParam long page,
                                       Authentication authentication) {
        return apiKeyService.page(userId, pageSize, page, authentication);
    }

    /**
     * 创建资源。
     */
    @PostMapping("/users/{userId}/api-keys")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = AuditActions.API_KEY_CREATED, entityType = EntityType.API_KEY,
            entityId = "#result.id")
    public ApiKeyEntity create(@PathVariable UUID userId, @RequestBody ApiKeyRequest request,
                               Authentication authentication) {
        return apiKeyService.create(userId, request, authentication);
    }

    /**
     * 删除资源。
     */
    @DeleteMapping("/api-keys/{apiKeyId}")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = AuditActions.API_KEY_DELETED, entityType = EntityType.API_KEY, entityId = "#apiKeyId")
    public void delete(@PathVariable UUID apiKeyId, Authentication authentication) {
        apiKeyService.delete(apiKeyId, authentication);
    }

    /**
     * 更新资源。
     */
    @PatchMapping("/api-keys/{apiKeyId}")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = AuditActions.API_KEY_UPDATED, entityType = EntityType.API_KEY, entityId = "#apiKeyId")
    public ApiKeyEntity update(@PathVariable UUID apiKeyId, @RequestBody ApiKeyUpdateRequest request,
                               Authentication authentication) {
        return apiKeyService.update(apiKeyId, request, authentication);
    }
}
