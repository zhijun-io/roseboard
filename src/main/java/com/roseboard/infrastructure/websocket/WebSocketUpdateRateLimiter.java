package com.roseboard.infrastructure.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WebSocketUpdateRateLimiter {
    private record Window(long count, long seconds) {
    }

    private final StringRedisTemplate redis;
    private final TenantProfileWsLimits wsLimits;

    @Value("${server.ws.updates_rate_limit:}")
    private String globalRateLimit;

    public WebSocketUpdateRateLimiter(StringRedisTemplate redis, TenantProfileWsLimits wsLimits) {
        this.redis = redis;
        this.wsLimits = wsLimits;
    }

    public boolean check(UUID tenantId, String sessionId) {
        String raw = resolveRateLimit(tenantId);
        if (!StringUtils.hasText(raw)) {
            return true;
        }
        List<Window> windows = parseWindows(raw);
        if (windows.isEmpty()) {
            return true;
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        for (Window window : windows) {
            long bucket = nowSeconds / window.seconds();
            String key = "roseboard:ws:updates:" + sessionId + ":" + window.seconds() + ":" + bucket;
            Long used = redis.opsForValue().increment(key);
            if (used != null && used.equals(1L)) {
                redis.expire(key, Duration.ofSeconds(window.seconds() + 1));
            }
            if (used != null && used > window.count()) {
                return false;
            }
        }
        return true;
    }

    private String resolveRateLimit(UUID tenantId) {
        if (tenantId != null) {
            String profileLimit = wsLimits.wsUpdatesPerSessionRateLimit(tenantId);
            if (StringUtils.hasText(profileLimit)) {
                return profileLimit;
            }
        }
        return globalRateLimit;
    }

    /** ThingsBoard-style: {@code count:seconds[,count:seconds...]}. */
    private static List<Window> parseWindows(String raw) {
        List<Window> windows = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            String[] bits = trimmed.split(":");
            if (bits.length != 2) {
                throw new IllegalArgumentException("Invalid WS update rate limit segment: " + trimmed);
            }
            long count = Long.parseLong(bits[0].trim());
            long seconds = Long.parseLong(bits[1].trim());
            if (count <= 0 || seconds <= 0) {
                throw new IllegalArgumentException("WS update rate limit count/seconds must be positive: " + trimmed);
            }
            windows.add(new Window(count, seconds));
        }
        return List.copyOf(windows);
    }
}
