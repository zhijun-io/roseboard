package com.roseboard.setting.mfa;

import com.roseboard.common.JacksonUtils;
import com.roseboard.infrastructure.security.jwt.mfa.BackupCodeMfaProvider;
import com.roseboard.infrastructure.security.jwt.mfa.TotpMfaProvider;
import com.roseboard.infrastructure.security.jwt.mfa.MfaLoginPolicy;
import com.roseboard.setting.mfa.provider.EmailMfaProvider;
import com.roseboard.setting.mfa.provider.SmsMfaProvider;
import com.roseboard.setting.AdminSettingService;
import com.roseboard.user.setting.UserSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.UUID;

@Service
public class MfaService implements MfaLoginPolicy {
    private final UserSettingService userSettingService;
    private final StringRedisTemplate redis;
    private static final String MFA_FAILURE_PREFIX = "roseboard:mfa:failure:";
    private static final String MFA_LOCK_PREFIX = "roseboard:mfa:lock:";
    private final boolean enabled;
    private final AdminSettingService adminSettingService;
    private final TotpMfaProvider totpProvider;
    private final EmailMfaProvider emailProvider;
    private final SmsMfaProvider smsProvider;
    private final BackupCodeMfaProvider backupCodeProvider;

    public MfaService(UserSettingService userSettingService,
                      AdminSettingService adminSettingService,
                      StringRedisTemplate redis,
                      TotpMfaProvider totpProvider, EmailMfaProvider emailProvider,
                      SmsMfaProvider smsProvider,
                      BackupCodeMfaProvider backupCodeProvider,
                      @Value("${roseboard.security.mfa.enabled:true}") boolean enabled) {
        this.userSettingService = userSettingService;
        this.adminSettingService = adminSettingService;
        this.redis = redis;
        this.totpProvider = totpProvider;
        this.emailProvider = emailProvider;
        this.smsProvider = smsProvider;
        this.backupCodeProvider = backupCodeProvider;
        this.enabled = enabled;
    }

    public JsonNode settings(UUID userId) {
        JsonNode value = userSettingService.get(userId, UserSettingService.MFA);
        if (value != null && value.isObject()) {
            return value;
        }
        ObjectNode root = JacksonUtils.objectMapper().createObjectNode();
        root.putObject("configs");
        return root;
    }

    public JsonNode generateTotp(String email) {
        ensureProviderEnabled("TOTP");
        return totpProvider.generate(null, email);
    }

    public JsonNode generateEmail(String email) {
        ensureProviderEnabled("EMAIL");
        return emailProvider.generate(null, email);
    }

    public JsonNode generateSms(String phoneNumber) {
        ensureProviderEnabled("SMS");
        return smsProvider.generate(null, phoneNumber);
    }

    public JsonNode generateBackupCodeConfig() {
        ensureProviderEnabled("BACKUP_CODE");
        return backupCodeProvider.generate(null, null);
    }

    public boolean isProviderEnabled(String providerType) {
        MfaSetting platform = adminSettingService.getMfaSettings();
        if (platform == null || platform.getProviders() == null || platform.getProviders().isEmpty()) return true;
        return platform.getProviders().stream().anyMatch(provider ->
                provider.getProviderType() != null && providerType.equalsIgnoreCase(provider.getProviderType().name()));
    }

    private void ensureProviderEnabled(String providerType) {
        if (!isProviderEnabled(providerType)) {
            throw new IllegalArgumentException("MFA provider is disabled: " + providerType);
        }
    }

    public boolean verifyBackupCode(UUID userId, String code) {
        ObjectNode root = (ObjectNode) settings(userId).deepCopy();
        JsonNode backup = root.path("configs").path("BACKUP_CODE");
        if (!backupCodeProvider.verify(userId, backup, code)) return false;
        persist(userId, root);
        return true;
    }

    private void persist(UUID userId, ObjectNode root) {
        userSettingService.save(userId, UserSettingService.MFA, root.toString());
    }

    public boolean requiresVerification(UUID userId) {
        if (!enabled) return false;
        MfaSetting platform = adminSettingService.getMfaSettings();
        if (platform == null || !platform.isEnforceTwoFa()) return false;
        JsonNode configs = settings(userId).path("configs");
        return configs.properties().stream().anyMatch(entry ->
                configs.path(entry.getKey()).path("useByDefault").asBoolean(false));
    }

    public boolean requiresConfiguration(UUID userId) {
        if (!enabled) return false;
        MfaSetting platform = adminSettingService.getMfaSettings();
        if (platform == null || !platform.isEnforceTwoFa()) return false;
        JsonNode configs = settings(userId).path("configs");
        return configs.isObject() && configs.properties().stream().findAny().isEmpty();
    }

    public void save(UUID userId, JsonNode config, String verificationCode) {
        String provider = config.path("providerType").asString();
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("MFA providerType is required");
        }
        ensureProviderEnabled(provider);
        if ("TOTP".equals(provider) && !totpProvider.verifyCode(config.path("secret").asString(), verificationCode)) {
            throw new IllegalArgumentException("Verification code is incorrect");
        }
        if ("EMAIL".equals(provider) && !verifyEmailCode(userId, verificationCode)) {
            throw new IllegalArgumentException("Verification code is incorrect");
        }
        if ("SMS".equals(provider) && !verifySmsCode(userId, config, verificationCode)) {
            throw new IllegalArgumentException("Verification code is incorrect");
        }
        if ("SMS".equals(provider)) {
            smsProvider.clearSendRateLimit(userId);
        }
        ObjectNode root = (ObjectNode) settings(userId).deepCopy();
        root.withObject("configs").set(provider, config);
        userSettingService.save(userId, UserSettingService.MFA, root.toString());
    }

    public void delete(UUID userId, String provider) {
        ObjectNode root = (ObjectNode) settings(userId).deepCopy();
        root.withObject("configs").remove(provider);
        userSettingService.save(userId, UserSettingService.MFA, root.toString());
    }

    public void sendEmailCode(UUID userId, String email) {
        emailProvider.send(userId, email);
    }

    public boolean verifyEmailCode(UUID userId, String code) {
        return emailProvider.verify(userId, null, code);
    }

    public void sendSmsCode(UUID userId, String phoneNumber) {
        smsProvider.send(userId, phoneNumber);
    }

    public boolean verifySmsCode(UUID userId, JsonNode config, String code) {
        return smsProvider.verify(userId, config, code);
    }

    public boolean verifyTotpAttempt(UUID userId, String secret, String code) {
        String lockKey = MFA_LOCK_PREFIX + userId;
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) return false;
        if (totpProvider.verifyCode(secret, code)) {
            redis.delete(MFA_FAILURE_PREFIX + userId);
            return true;
        }
        String failureKey = MFA_FAILURE_PREFIX + userId;
        Long failures = redis.opsForValue().increment(failureKey);
        redis.expire(failureKey, Duration.ofMinutes(5));
        if (failures != null && failures >= 5) {
            redis.opsForValue().set(lockKey, "1", Duration.ofMinutes(5));
            redis.delete(failureKey);
        }
        return false;
    }

    public boolean consumePreVerificationToken(String tokenId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                "roseboard:mfa:pre-consumed:" + tokenId, "1", Duration.ofMinutes(5)));
    }

    public boolean verifyTotp(String secret, String code) {
        return totpProvider.verifyCode(secret, code);
    }
}
