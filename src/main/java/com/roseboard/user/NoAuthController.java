package com.roseboard.user;

import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.setting.AdminSettingService;
import com.roseboard.setting.PasswordPolicy;
import com.roseboard.user.credential.UserCredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 无需登录的认证辅助接口：密码重置、账户激活和密码策略查询。
 */
@RestController
@RequestMapping("/api/noauth")
public class NoAuthController {
    private final UserCredentialService userCredentialService;
    private final AdminSettingService adminSettingService;

    public NoAuthController(UserCredentialService userCredentialService, AdminSettingService adminSettingService) {
        this.userCredentialService = userCredentialService;
        this.adminSettingService = adminSettingService;
    }

    /**
     * 查询密码策略。
     */
    @GetMapping("/userPasswordPolicy")
    public PasswordPolicy getUserPasswordPolicy() {
        return adminSettingService.getSecuritySettings().getPasswordPolicy();
    }

    /**
     * 校验用户激活令牌。
     */
    @GetMapping("/activate")
    public ResponseEntity<Void> checkActivateToken(@RequestParam String activateToken) {
        return userCredentialService.isActivateTokenValid(activateToken)
                ? ResponseEntity.ok().build()
                : ResponseEntity.badRequest().build();
    }

    /**
     * 校验密码重置令牌。
     */
    @GetMapping("/resetPassword")
    public ResponseEntity<Void> checkResetToken(@RequestParam String resetToken) {
        return userCredentialService.isResetTokenValid(resetToken)
                ? ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/login/resetPassword?resetToken=" + resetToken))
                .build()
                : ResponseEntity.badRequest().build();
    }

    /**
     * 发起密码重置邮件。
     */
    @PostMapping("/resetPasswordByEmail")
    public void requestResetPasswordByEmail(@RequestBody ResetPasswordEmailRequest request) {
        userCredentialService.requestPasswordResetByEmail(request.email());
    }

    /**
     * 使用重置令牌设置新密码。
     */
    @PostMapping("/resetPassword")
    public void resetPassword(@RequestBody ResetPasswordRequest request) {
        userCredentialService.resetPasswordAndNotify(request.resetToken(), request.password());
    }

    /**
     * 激活用户并完成登录。
     */
    @PostMapping("/activate")
    public LoginResponse activateUser(@RequestBody ActivateUserRequest request) {
        return userCredentialService.activateUserAndLogin(request.activateToken(), request.password());
    }

    public record ResetPasswordEmailRequest(String email) {}
    public record ResetPasswordRequest(String resetToken, String password) {}
    public record ActivateUserRequest(String activateToken, String password) {}
}
