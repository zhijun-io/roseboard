package com.roseboard.user.credential;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.api.Operation;
import com.roseboard.infrastructure.security.api.RequirePermission;
import com.roseboard.infrastructure.security.api.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 用户凭据接口：处理激活、密码、激活令牌和凭据状态。
 */
@RestController
@RequestMapping("/api")
public class UserCredentialController {
    private final UserCredentialService credentialsService;

    public UserCredentialController(UserCredentialService credentialsService) {
        this.credentialsService = credentialsService;
    }

    /**
     * 发送用户激活邮件。
     */


    @PostMapping("/users/activation")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = AuditActions.USER_ACTIVATION_MAIL_SENT, entityType = EntityType.USER,
            entityName = "#email")
    public void sendActivationMail(@RequestParam String email) {
        credentialsService.sendActivationMail(email);
    }

    /**
     * 修改当前用户密码并刷新登录凭据。
     */
    @PutMapping("/users/me/password")
    @PreAuthorize("isAuthenticated()")
    @Audited(action = AuditActions.PASSWORD_CHANGED, entityType = EntityType.USER,
            entityId = "#principal.userId", entityName = "#authentication.name")
    public LoginResponse changePassword(@RequestBody ChangePasswordRequest request,
                                        Authentication authentication) {
        return credentialsService.changePassword(request.currentPassword(), request.newPassword(), authentication);
    }

    /**
     * 查询用户激活链接。
     */
    @GetMapping(value = "/users/{userId}/activation-link", produces = "text/plain")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public String activationLink(@PathVariable UUID userId, Authentication authentication) {
        return credentialsService.activationLink(userId, authentication);
    }

    /**
     * 启用或禁用用户凭据登录。
     */
    @PutMapping("/users/{userId}/credential")
    @RequirePermission(resource = EntityType.USER, operation = Operation.WRITE)
    @Audited(action = AuditActions.USER_CREDENTIALS_UPDATED, entityType = EntityType.USER,
            entityId = "#userId")
    public void setUserCredentialsEnabled(@PathVariable UUID userId,
                                           @RequestParam boolean userCredentialsEnabled,
                                           Authentication authentication) {
        credentialsService.setEnabled(userId, userCredentialsEnabled, authentication);
    }

    /**
     * 查询用户激活令牌信息。
     */
    @GetMapping("/users/{userId}/activation-token")
    @RequirePermission(resource = EntityType.USER, operation = Operation.READ)
    public ActivationLinkInfo activationLinkInfo(@PathVariable UUID userId,
                                                 Authentication authentication) {
        return credentialsService.activationLinkInfo(userId, authentication);
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
