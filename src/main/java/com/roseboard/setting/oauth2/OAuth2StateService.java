package com.roseboard.setting.oauth2;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class OAuth2StateService {
    private static final String PREFIX = "roseboard:oauth2:state:";
    private final StringRedisTemplate redis;

    public OAuth2StateService(StringRedisTemplate redis) { this.redis = redis; }

    public String create(UUID clientId) {
        String state = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + state, clientId.toString(), Duration.ofMinutes(5));
        return state;
    }

    public UUID consume(String state) {
        if (state == null || state.isBlank()) return null;
        String clientId = redis.opsForValue().getAndDelete(PREFIX + state);
        return clientId == null ? null : UUID.fromString(clientId);
    }
}
