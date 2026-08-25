package com.roseboard.setting.mfa.provider;

import com.roseboard.infrastructure.security.jwt.mfa.MfaProvider;
import com.roseboard.infrastructure.security.jwt.mfa.MfaProviderType;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyOptions;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SmsMfaProvider implements MfaProvider {
    private final StringRedisTemplate redis;
    private final NotificationCenter notificationCenter;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public SmsMfaProvider(StringRedisTemplate redis, NotificationCenter notificationCenter,
                          ObjectMapper objectMapper) {
        this.redis = redis;
        this.notificationCenter = notificationCenter;
        this.objectMapper = objectMapper;
    }

    @Override
    public MfaProviderType type() {
        return MfaProviderType.SMS;
    }

    @Override
    public JsonNode generate(UUID userId, String phoneNumber) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("providerType", type().name());
        config.put("useByDefault", false);
        if (phoneNumber != null) {
            config.put("phoneNumber", phoneNumber);
        } else {
            config.putNull("phoneNumber");
        }
        return config;
    }

    @Override
    public boolean verify(UUID userId, JsonNode config, String code) {
        String expected = redis.opsForValue().get(key(userId));
        if (expected == null || !expected.equals(code)) {
            return false;
        }
        redis.delete(key(userId));
        return true;
    }

    public void clearSendRateLimit(UUID userId) {
        redis.delete(rateKey(userId));
    }

    @Override
    public void send(UUID userId, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("SMS phone number is required");
        }
        String rateKey = rateKey(userId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(rateKey, "1", Duration.ofSeconds(60)))) {
            throw new IllegalStateException("MFA code requested too frequently");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        try {
            notificationCenter.notify(null, new NotifyCommand(
                    Set.of(ChannelKind.SMS),
                    List.of(new RecipientRef(userId, null, phoneNumber)),
                    "AUTH",
                    "auth.mfa-sms",
                    Map.of("code", code),
                    NotifyOptions.systemNotification()));
            redis.opsForValue().set(key(userId), code, Duration.ofMinutes(5));
        } catch (RuntimeException exception) {
            redis.delete(rateKey);
            throw exception;
        }
    }

    private String key(UUID userId) {
        return "roseboard:mfa:sms:" + userId;
    }

    private String rateKey(UUID userId) {
        return "roseboard:mfa:sms:rate:" + userId;
    }
}
