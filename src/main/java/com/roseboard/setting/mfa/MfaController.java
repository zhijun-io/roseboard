package com.roseboard.setting.mfa;

import com.roseboard.audit.AuditActions;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.Audited;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.setting.security.DefaultSecurityUserService;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.jwt.mfa.MfaProviderType;
import com.roseboard.infrastructure.security.jwt.JwtTokenType;
import com.roseboard.infrastructure.security.api.LoginResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MFA 接口：管理当前用户 MFA 配置并处理登录验证流程。
 */
@RestController
@RequestMapping("/api")
public class MfaController {
    private final MfaService mfaService;
    private final JwtTokenFactory tokenService;
    private final DefaultSecurityUserService userDetailsService;
    private final JwtDecoder jwtDecoder;
    private final ApplicationEventPublisher eventPublisher;

    public MfaController(MfaService mfaService, JwtTokenFactory tokenService,
                         DefaultSecurityUserService userDetailsService,
                         JwtDecoder jwtDecoder,
                         ApplicationEventPublisher eventPublisher) {
        this.mfaService = mfaService;
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.jwtDecoder = jwtDecoder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 查询可用 MFA 提供商。
     */
    @GetMapping("/mfa/providers")
    public List<MfaProviderType> availableProviders(Authentication authentication) {
        return Arrays.stream(MfaProviderType.values())
                .filter(provider -> mfaService.isProviderEnabled(provider.name())).toList();
    }

    /**
     * 查询当前用户 MFA 设置。
     */
    @GetMapping("/users/me/mfa-settings")
    public JsonNode settings(Authentication authentication) {
        return mfaService.settings(userId(authentication));
    }

/**
 * 生成 MFA 配置。
 */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN','CUSTOMER_USER','MFA_CONFIGURATION_TOKEN')")
    /**
     * 生成 MFA 配置。
     */
    @PostMapping("/users/me/mfa-configuration-drafts")
    @Audited(action = AuditActions.MFA_CONFIGURED, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public JsonNode generate(@RequestParam(defaultValue = "TOTP") MfaProviderType providerType,
                             @RequestParam(required = false) String phoneNumber,
                             Authentication authentication) {
        return switch (providerType) {
            case TOTP -> mfaService.generateTotp(username(authentication));
            case EMAIL -> mfaService.generateEmail(username(authentication));
            case SMS -> mfaService.generateSms(phoneNumber);
            case BACKUP_CODE -> mfaService.generateBackupCodeConfig();
        };
    }

/**
 * 创建或更新资源。
 */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN','CUSTOMER_USER','MFA_CONFIGURATION_TOKEN')")
    /**
     * 创建或更新资源。
     */
    @PostMapping("/users/me/mfa-configurations")
    @Audited(action = AuditActions.MFA_CONFIGURED, entityType = EntityType.USER)
    public void save(@RequestBody JsonNode config,
                     @RequestParam(required = false) MfaProviderType providerType,
                     @RequestParam(required = false) String verificationCode,
                     Authentication authentication) {
        if (config == null || !config.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA config must be an object");
        }
        ObjectNode normalized = (ObjectNode) config.deepCopy();
        if ((normalized.path("providerType").isMissingNode() || normalized.path("providerType").isNull())
                && providerType != null) {
            normalized.put("providerType", providerType.name());
        }
        try {
            mfaService.save(userId(authentication), normalized, verificationCode);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

/**
 * 更新资源。
 */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN','CUSTOMER_USER','MFA_CONFIGURATION_TOKEN')")
    /**
     * 更新资源。
     */
    @PutMapping("/users/me/mfa-configurations/{providerType}")
    @Audited(action = AuditActions.MFA_CONFIGURED, entityType = EntityType.USER)
    public void update(@RequestBody JsonNode config, @PathVariable MfaProviderType providerType,
                       @RequestParam(required = false) String verificationCode,
                       Authentication authentication) {
        mfaService.delete(userId(authentication), providerType.name());
        mfaService.save(userId(authentication), config, verificationCode);
    }

/**
 * 删除资源。
 */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN','CUSTOMER_USER','MFA_CONFIGURATION_TOKEN')")
    /**
     * 删除资源。
     */
    @DeleteMapping("/users/me/mfa-configurations/{providerType}")
    @Audited(action = AuditActions.MFA_CONFIGURED, entityType = EntityType.USER)
    public void delete(@PathVariable MfaProviderType providerType, Authentication authentication) {
        mfaService.delete(userId(authentication), providerType.name());
    }

/**
 * 完成 MFA 配置后的登录。
 */
    @PreAuthorize("isAuthenticated()")
    /**
     * 完成 MFA 配置后的登录。
     */
    @PostMapping("/login/mfa/session")
    @Audited(action = AuditActions.LOGIN_SUCCESS, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public LoginResponse loginAfterConfiguration(
            @RequestHeader("Authorization") String authorization,
            Authentication authentication) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !JwtTokenType.MFA_CONFIGURATION.value().equals(
                jwtDecoder.decode(authorization.substring("Bearer ".length()))
                        .getClaimAsString("tokenType"))) {
            throw new org.springframework.security.access.AccessDeniedException("MFA configuration token required");
        }
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(username(authentication));
        return tokenService.login(principal);
    }

/**
 * 查询当前登录流程可用的 MFA 提供商。
 */
    @PreAuthorize("hasAuthority('MFA_PRE_VERIFICATION')")
    /**
     * 查询当前登录流程可用的 MFA 提供商。
     */
    @GetMapping("/login/mfa/providers")
    public List<MfaProviderType> providers(Authentication authentication) {
        JsonNode configs = mfaService.settings(userId(authentication)).path("configs");
        List<MfaProviderType> result = new ArrayList<>();
        for (MfaProviderType provider : MfaProviderType.values()) {
            if (!configs.path(provider.name()).isMissingNode()) result.add(provider);
        }
        return result;
    }

/**
 * 发送通知或验证码。
 */
    @PreAuthorize("hasAuthority('MFA_PRE_VERIFICATION')")
    /**
     * 发送通知或验证码。
     */
    @PostMapping("/login/mfa/verification-codes")
    @Audited(action = AuditActions.MFA_CODE_SENT, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public void send(@RequestParam MfaProviderType providerType, Authentication authentication) {
        UUID id = userId(authentication);
        try {
            if (!mfaService.isProviderEnabled(providerType.name())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "MFA provider is disabled: " + providerType);
            }
            switch (providerType) {
                case EMAIL -> mfaService.sendEmailCode(id, username(authentication));
                case SMS -> {
                    String phone = mfaService.settings(id).path("configs").path("SMS")
                            .path("phoneNumber").asString(null);
                    mfaService.sendSmsCode(id, phone);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Provider does not support code sending");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

/**
 * 发送 MFA 配置验证码。
 */
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN','TENANT_ADMIN','CUSTOMER_USER','MFA_CONFIGURATION_TOKEN')")
    /**
     * 发送 MFA 配置验证码。
     */
    @PostMapping("/users/me/mfa-configurations/{providerType}/verification-codes")
    @Audited(action = AuditActions.MFA_CODE_SENT, entityType = EntityType.USER,
            entityId = "#principal.userId")
    public void sendConfigurationCode(@PathVariable MfaProviderType providerType,
                                       @RequestParam(required = false) String phoneNumber,
                                       Authentication authentication) {
        UUID id = userId(authentication);
        try {
            switch (providerType) {
                case SMS -> mfaService.sendSmsCode(id, phoneNumber);
                case EMAIL -> mfaService.sendEmailCode(id, username(authentication));
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Provider does not support code sending");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 校验 MFA 验证码并完成登录。
     */
    @PostMapping("/login/mfa/verifications")
    @PreAuthorize("hasAuthority('MFA_PRE_VERIFICATION')")
    public LoginResponse check(@RequestParam MfaProviderType providerType, @RequestParam String verificationCode,
                               @RequestHeader("Authorization") String authorization,
                               Authentication authentication) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MFA pre-verification token required");
        }
        Jwt preVerificationToken = jwtDecoder.decode(authorization.substring("Bearer ".length()));
        if (!JwtTokenType.MFA_PRE_VERIFICATION.value().equals(preVerificationToken.getClaimAsString("tokenType"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MFA pre-verification token required");
        }
        UUID id = userId(authentication);
        String providerName = providerType.name();
        if (!mfaService.isProviderEnabled(providerName)
                || mfaService.settings(id).path("configs").path(providerName).isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MFA provider is unavailable: " + providerType);
        }
        boolean verified = switch (providerType) {
            case BACKUP_CODE -> mfaService.verifyBackupCode(id, verificationCode);
            case EMAIL -> mfaService.verifyEmailCode(id, verificationCode);
            case SMS -> mfaService.verifySmsCode(id, mfaService.settings(id).path("configs").path(providerName), verificationCode);
            case TOTP -> {
                String secret = mfaService.settings(id).path("configs").path(providerName).path("secret").asString();
                yield mfaService.verifyTotpAttempt(id, secret, verificationCode);
            }
        };
        String mfaDetail = "{\"providerType\":\"" + providerType + "\"}";
        if (!verified) {
            SecurityUser failed = (SecurityUser) userDetailsService.loadUserByUsername(username(authentication));
            eventPublisher.publishEvent(AuditEvent.failure(AuditActions.MFA_FAILED, failed,
                    AuditTarget.user(failed), mfaDetail, "Verification code is incorrect", null));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification code is incorrect");
        }
        if (!mfaService.consumePreVerificationToken(preVerificationToken.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA pre-verification token already used");
        }
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(username(authentication));
        eventPublisher.publishEvent(AuditEvent.success(AuditActions.MFA_VERIFIED, principal, mfaDetail));
        return tokenService.login(principal);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason()));
    }

    private UUID userId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUser user) return user.getUserId();
        if (principal instanceof Jwt jwt) return UUID.fromString(jwt.getClaimAsString("userId"));
        throw new IllegalArgumentException("Unsupported authentication principal");
    }

    private String username(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUser user) return user.getUsername();
        if (principal instanceof Jwt jwt) return jwt.getSubject();
        throw new IllegalArgumentException("Unsupported authentication principal");
    }
}
