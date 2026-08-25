package com.roseboard.user.credential;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyOptions;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.setting.security.DefaultSecurityUserService;
import com.roseboard.setting.security.DataScopeAuthorizer;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.jwt.JwtTokenFactory;
import com.roseboard.setting.PasswordPolicyService;
import com.roseboard.user.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class UserCredentialService {
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private final UserMapper userMapper;
    private final UserCredentialMapper credentialsMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordService passwordService;
    private final ApplicationEventPublisher eventPublisher;
    private final DataScopeAuthorizer dataScopeService;
    private final NotificationCenter notificationCenter;
    private final DefaultSecurityUserService userDetailsService;
    private final JwtTokenFactory tokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserCredentialService(UserMapper userMapper,
                                 UserCredentialMapper credentialsMapper,
                                 PasswordEncoder passwordEncoder,
                                 PasswordPolicyService passwordPolicyService,
                                 PasswordService passwordService,
                                 ApplicationEventPublisher eventPublisher,
                                 DataScopeAuthorizer dataScopeService,
                                 NotificationCenter notificationCenter,
                                 DefaultSecurityUserService userDetailsService,
                                 JwtTokenFactory tokenService) {
        this.userMapper = userMapper;
        this.credentialsMapper = credentialsMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordService = passwordService;
        this.eventPublisher = eventPublisher;
        this.dataScopeService = dataScopeService;
        this.notificationCenter = notificationCenter;
        this.userDetailsService = userDetailsService;
        this.tokenService = tokenService;
    }

    public LoginResponse changePassword(String currentPassword, String newPassword,
                                        Authentication authentication) {
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(authentication.getName());
        passwordService.changePassword(principal.getUserId(), currentPassword, newPassword);
        tokenService.revokeAll(principal.getUserId());
        JwtTokenFactory.TokenPair tokens = tokenService.issue(principal);
        return new LoginResponse(tokens.token(), tokens.refreshToken());
    }

    public void requestPasswordResetByEmail(String email) {
        String resetToken = requestPasswordReset(email);
        if (resetToken == null) {
            return;
        }
        UserEntity user = userMapper.findByEmail(email);
        if (user != null) {
            sendAuthEmail(user, "auth.password-reset",
                    Map.of("link", "/api/noauth/resetPassword?resetToken=" + resetToken));
        }
    }

    public void resetPasswordAndNotify(String resetToken, String password) {
        String email = resetPassword(resetToken, password);
        if (email == null) {
            return;
        }
        UserEntity user = userMapper.findByEmail(email);
        if (user != null) {
            sendAuthEmail(user, "auth.password-was-reset", Map.of("link", "/login"));
        }
    }

    public LoginResponse activateUserAndLogin(String activateToken, String password) {
        UserEntity user = activateUser(activateToken, password);
        sendAuthEmail(user, "auth.account-activated", Map.of("link", "/login"));
        SecurityUser principal = (SecurityUser) userDetailsService.loadUserByUsername(user.getEmail());
        return tokenService.login(principal);
    }
    public void sendActivationMail(String email) {
        String token = activationTokenForEmail(email);
        UserEntity user = userMapper.findByEmail(email);
        if (token != null && user != null) {
            sendActivationEmail(user, token);
        }
    }

    public String activationTokenForEmail(String email) {
        UserEntity user = userMapper.findByEmail(email);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        UserCredentialEntity credentials = requireCredentials(user.getId());
        if (Boolean.TRUE.equals(credentials.getEnabled())) {
            return null;
        }
        if (credentials.getActivateToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activation token unavailable");
        }
        return credentials.getActivateToken();
    }

    public String activationLink(UUID userId) {
        UserCredentialEntity credentials = requireCredentials(userId);
        if (credentials.getActivateToken() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activation token unavailable");
        }
        return "/api/noauth/activate?activateToken=" + credentials.getActivateToken();
    }

    public String activationLink(UUID userId, Authentication authentication) {
        requireScopedUser(userId, authentication);
        return activationLink(userId);
    }

    public ActivationLinkInfo activationLinkInfo(UUID userId) {
        UserCredentialEntity credentials = requireCredentials(userId);
        ActivationLinkInfo result = new ActivationLinkInfo();
        result.setActivateToken(credentials.getActivateToken() == null ? "" : credentials.getActivateToken());
        result.setActivateTokenExpTime(credentials.getActivateTokenExpTime() == null ? 0L : credentials.getActivateTokenExpTime());
        return result;
    }

    public ActivationLinkInfo activationLinkInfo(UUID userId, Authentication authentication) {
        requireScopedUser(userId, authentication);
        return activationLinkInfo(userId);
    }

    public void setEnabled(UUID userId, boolean enabled) {
        UserCredentialEntity credentials = requireCredentials(userId);
        credentials.setEnabled(enabled);
        credentialsMapper.updateById(credentials);
    }

    public void setEnabled(UUID userId, boolean enabled, Authentication authentication) {
        requireScopedUser(userId, authentication);
        setEnabled(userId, enabled);
    }

    public String createForUser(UUID userId, long createdTime) {
        UserCredentialEntity credentials = new UserCredentialEntity();
        credentials.setId(UUID.randomUUID());
        credentials.setCreatedTime(createdTime);
        credentials.setUserId(userId);
        credentials.setEnabled(false);
        credentials.setActivateToken(generateToken());
        credentials.setActivateTokenExpTime(Instant.now().plus(TOKEN_TTL).toEpochMilli());
        credentials.setAdditionalInfo("{}");
        credentialsMapper.insert(credentials);
        return credentials.getActivateToken();
    }

    public void activate(UUID userId) {
        UserCredentialEntity credentials = requireCredentials(userId);
        credentials.setEnabled(true);
        credentials.setActivateToken(null);
        credentials.setActivateTokenExpTime(null);
        credentialsMapper.updateById(credentials);
    }

    public void deleteByUserId(UUID userId) {
        credentialsMapper.deleteByUserId(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public String requestPasswordReset(String email) {
        UserEntity user = userMapper.findByEmail(email);
        if (user == null) {
            eventPublisher.publishEvent(AuditEvent.failure(
                    AuditActions.PASSWORD_RESET_PROBE,
                    SecurityUsers.anonymous(),
                    AuditTarget.loginAttempt(),
                    "Unknown email"));
            return null;
        }
        UserCredentialEntity credentials = credentialsMapper.findByUserId(user.getId());
        if (credentials == null) {
            return null;
        }
        String resetToken = generateToken();
        credentials.setResetToken(resetToken);
        credentials.setResetTokenExpTime(Instant.now().plus(TOKEN_TTL).toEpochMilli());
        credentialsMapper.updateById(credentials);
        eventPublisher.publishEvent(AuditEvent.success(
                AuditActions.PASSWORD_RESET_REQUESTED,
                SecurityUsers.anonymous(),
                new AuditTarget(EntityType.USER, user.getId(), user.getEmail(),
                        user.getTenantId(), user.getCustomerId())));
        return resetToken;
    }

    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(String resetToken, String password) {
        UserCredentialEntity credentials = findByResetToken(resetToken);
        ensureTokenValid(credentials.getResetTokenExpTime(), "Reset token expired");
        passwordPolicyService.validate(password);
        credentialsMapper.updatePasswordAndClearResetToken(
                credentials.getId(), passwordEncoder.encode(password));
        UserEntity user = userMapper.selectById(credentials.getUserId());
        if (user != null) {
            eventPublisher.publishEvent(AuditEvent.anonymousUserSuccess(
                    AuditActions.PASSWORD_RESET, user.getId(), user.getTenantId(), user.getCustomerId()));
        }
        return user == null ? null : user.getEmail();
    }

    @Transactional(rollbackFor = Exception.class)
    public UserEntity activateUser(String activateToken, String password) {
        UserCredentialEntity credentials = credentialsMapper.findByActivateToken(activateToken);
        if (credentials == null) {
            throw new IllegalArgumentException("Invalid activate token");
        }
        ensureTokenValid(credentials.getActivateTokenExpTime(), "Activate token expired");
        passwordPolicyService.validate(password);
        credentials.setPassword(passwordEncoder.encode(password));
        credentials.setEnabled(true);
        credentials.setActivateToken(null);
        credentials.setActivateTokenExpTime(null);
        credentialsMapper.updateById(credentials);
        UserEntity user = userMapper.selectById(credentials.getUserId());
        if (user != null) {
            eventPublisher.publishEvent(AuditEvent.anonymousUserSuccess(
                    AuditActions.USER_ACTIVATED, user.getId(), user.getTenantId(), user.getCustomerId()));
        }
        return user;
    }

    public boolean isActivateTokenValid(String activateToken) {
        if (activateToken == null || activateToken.isBlank()) {
            return false;
        }
        UserCredentialEntity credentials = credentialsMapper.findByActivateToken(activateToken);
        return credentials != null && isNotExpired(credentials.getActivateTokenExpTime());
    }

    public boolean isResetTokenValid(String resetToken) {
        if (resetToken == null || resetToken.isBlank()) {
            return false;
        }
        UserCredentialEntity credentials = credentialsMapper.findByResetToken(resetToken);
        return credentials != null && isNotExpired(credentials.getResetTokenExpTime());
    }

    private UserCredentialEntity requireCredentials(UUID userId) {
        UserCredentialEntity credentials = credentialsMapper.findByUserId(userId);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Credentials not found");
        }
        return credentials;
    }

    private UserCredentialEntity findByResetToken(String resetToken) {
        UserCredentialEntity credentials = credentialsMapper.findByResetToken(resetToken);
        if (credentials == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reset token");
        }
        return credentials;
    }

    private boolean isNotExpired(Long expiresAt) {
        return expiresAt != null && expiresAt >= Instant.now().toEpochMilli();
    }

    private void ensureTokenValid(Long expiresAt, String message) {
        if (expiresAt == null || expiresAt < Instant.now().toEpochMilli()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private String generateToken() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void requireScopedUser(UUID userId, Authentication authentication) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        dataScopeService.requireUserScope(authentication, user);
    }

    public void sendActivationEmail(UserEntity user, String activateToken) {
        sendAuthEmail(user, "auth.activation",
                Map.of("link", "/api/noauth/activate?activateToken=" + activateToken));
    }

    private void sendAuthEmail(UserEntity user, String templateKey, Map<String, Object> vars) {
        notificationCenter.notify(user.getTenantId(), new NotifyCommand(
                Set.of(ChannelKind.EMAIL),
                List.of(new RecipientRef(user.getId(), user.getEmail(), null)),
                "AUTH",
                templateKey,
                vars,
                NotifyOptions.systemNotification()));
    }
}
