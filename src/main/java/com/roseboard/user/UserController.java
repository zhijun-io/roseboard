package com.roseboard.user;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.PageData;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.RequirePermission;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 用户资源接口：提供用户 CRUD、查询、Token 和范围内用户列表。
 */
@RestController
@RequestMapping("/api")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查询当前登录用户资料。
     */
    @GetMapping("/users/me")
    @PreAuthorize("isAuthenticated() and !hasAnyAuthority('MFA_CONFIGURATION_TOKEN','MFA_PRE_VERIFICATION')")
    public UserEntity currentUser(Authentication authentication) {
        return userService.findByEmail(authentication.getName());
    }

    /**
     * 按用户 ID 查询用户资料。
     */
    @GetMapping("/users/{userId}")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public UserEntity getUserById(@PathVariable UUID userId, Authentication authentication) {
        return userService.requireScoped(userId, authentication);
    }

    /**
     * 查询当前用户是否允许使用 Token 访问。
     */
    @GetMapping("/users/me/token-access")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public boolean tokenAccessEnabled() {
        return true;
    }

    /**
     * 为指定用户签发登录 Token。
     */
    @PostMapping("/users/{userId}/tokens")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public LoginResponse getUserToken(@PathVariable UUID userId, Authentication authentication) {
        return userService.issueUserToken(userId, authentication);
    }

    /**
     * 创建或更新用户。
     */
    @PostMapping("/users")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = "#payload.has('id') ? T(com.roseboard.audit.AuditActions).USER_UPDATED : T(com.roseboard.audit.AuditActions).USER_CREATED",
            entityType = EntityType.USER, entityId = "#result.id", entityName = "#result.email")
    public UserEntity saveUser(@RequestBody JsonNode payload,
                               @RequestParam(defaultValue = "true") boolean sendActivationMail,
                               Authentication authentication) {
        return userService.save(payload, sendActivationMail, authentication);
    }

    /**
     * 删除指定用户。
     */
    @DeleteMapping("/users/{userId}")
    @RequirePermission(resource = EntityType.USER, operation = Operation.DELETE)
    @Audited(action = AuditActions.USER_DELETED, entityType = EntityType.USER,
            entityId = "#userId")
    public void deleteUser(@PathVariable UUID userId, Authentication authentication) {
        userService.delete(userId, authentication);
    }

    /**
     * 按范围分页查询用户。
     */
    @GetMapping("/users")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public PageData<UserEntity> getUsers(@RequestParam long pageSize,
                                         @RequestParam long page,
                                         @RequestParam(required = false) String textSearch,
                                         Authentication authentication) {
        return userService.findUsers(authentication, pageSize, page, textSearch);
    }

    /**
     * 查询用户摘要列表。
     */
    @GetMapping("/user-summaries")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public java.util.List<UserEmailInfo> findUsersByQuery(Authentication authentication) {
        return userService.findUserInfoList(authentication);
    }

    /**
     * 查询租户下的用户。
     */
    @GetMapping("/tenants/{tenantId}/users")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public PageData<UserEntity> getTenantUsers(@PathVariable UUID tenantId,
                                               @RequestParam long pageSize,
                                               @RequestParam long page,
                                               @RequestParam(required = false) String textSearch,
                                               Authentication authentication) {
        return userService.findTenantUsers(tenantId, pageSize, page, textSearch, authentication);
    }

    /**
     * 查询客户下的用户。
     */
    @GetMapping("/customers/{customerId}/users")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public PageData<UserEntity> getCustomerUsers(@PathVariable UUID customerId,
                                                 @RequestParam long pageSize,
                                                 @RequestParam long page,
                                                 @RequestParam(required = false) String textSearch,
                                                 Authentication authentication) {
        return userService.findCustomerUsers(customerId, pageSize, page, textSearch, authentication);
    }
}
