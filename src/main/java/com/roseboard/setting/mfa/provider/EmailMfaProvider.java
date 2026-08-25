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
public class EmailMfaProvider implements MfaProvider {
    private final StringRedisTemplate redis;
    private final NotificationCenter notificationCenter;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public EmailMfaProvider(StringRedisTemplate redis, NotificationCenter notificationCenter,
                            ObjectMapper objectMapper) {
        this.redis = redis;
        this.notificationCenter = notificationCenter;
        this.objectMapper = objectMapper;
    }

    @Override public MfaProviderType type() { return MfaProviderType.EMAIL; }

    @Override public JsonNode generate(UUID userId, String email) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("providerType", type().name());
        config.put("useByDefault", false);
        config.put("email", email);
        return config;
    }

    @Override public boolean verify(UUID userId, JsonNode config, String code) {
        String expected = redis.opsForValue().get(key(userId));
        if (expected == null || !expected.equals(code)) return false;
        redis.delete(key(userId));
        return true;
    }

    @Override public void send(UUID userId, String email) {
        String rateKey = "roseboard:mfa:email:rate:" + userId;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(rateKey, "1", Duration.ofSeconds(60)))) {
            throw new IllegalStateException("MFA code requested too frequently");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(key(userId), code, Duration.ofMinutes(5));
        notificationCenter.notify(null, new NotifyCommand(
                Set.of(ChannelKind.EMAIL),
                List.of(new RecipientRef(userId, email, null)),
                "AUTH",
                "auth.mfa-email",
                Map.of("code", code),
                NotifyOptions.systemNotification()));
    }

    private String key(UUID userId) { return "roseboard:mfa:email:" + userId; }
}
