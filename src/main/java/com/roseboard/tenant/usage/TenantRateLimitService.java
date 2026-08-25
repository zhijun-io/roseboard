package com.roseboard.tenant.usage;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TenantRateLimitService {
    public static final String TRANSPORT_TENANT_MSG = "transportTenantMsgRateLimit";
    public static final String TRANSPORT_DEVICE_MSG = "transportDeviceMsgRateLimit";
    public static final String TRANSPORT_TENANT_TELEMETRY_MSG = "transportTenantTelemetryMsgRateLimit";
    public static final String TRANSPORT_TENANT_TELEMETRY_DP = "transportTenantTelemetryDataPointsRateLimit";
    public static final String TRANSPORT_DEVICE_TELEMETRY_MSG = "transportDeviceTelemetryMsgRateLimit";
    public static final String TRANSPORT_DEVICE_TELEMETRY_DP = "transportDeviceTelemetryDataPointsRateLimit";
    public static final String TENANT_SERVER_REST = "tenantServerRestLimitsConfiguration";
    public static final String CUSTOMER_SERVER_REST = "customerServerRestLimitsConfiguration";

    private record Window(long count, long seconds) {
    }

    private final StringRedisTemplate redis;
    private final TenantUsageService usageService;

    public TenantRateLimitService(StringRedisTemplate redis, TenantUsageService usageService) {
        this.redis = redis;
        this.usageService = usageService;
    }

    public void consumeTenant(UUID tenantId, String profileKey, long cost) {
        if (tenantId == null || cost <= 0) {
            return;
        }
        consume("t:" + tenantId, tenantId, profileKey, cost);
    }

    public void consumeDevice(UUID tenantId, UUID deviceId, String profileKey, long cost) {
        if (tenantId == null || deviceId == null || cost <= 0) {
            return;
        }
        consume("d:" + deviceId, tenantId, profileKey, cost);
    }

    private void consume(String subject, UUID tenantId, String profileKey, long cost) {
        String raw = usageService.rateLimitConfiguration(tenantId, profileKey);
        if (raw == null) {
            return;
        }
        List<Window> windows;
        try {
            windows = parseWindows(raw);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid rate limit configuration for " + profileKey, exception);
        }
        if (windows.isEmpty()) {
            return;
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        for (Window window : windows) {
            long bucket = nowSeconds / window.seconds();
            String key = "roseboard:rl:" + profileKey + ":" + subject + ":" + window.seconds() + ":" + bucket;
            Long used = redis.opsForValue().increment(key, cost);
            if (used != null && used.equals(cost)) {
                redis.expire(key, Duration.ofSeconds(window.seconds() + 1));
            }
            if (used != null && used > window.count()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limit exceeded for " + profileKey);
            }
        }
    }

    /** ThingsBoard-style: {@code count:seconds[,count:seconds...]}. */
    private static List<Window> parseWindows(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<Window> windows = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            String[] bits = trimmed.split(":");
            if (bits.length != 2) {
                throw new IllegalArgumentException("Invalid rate limit segment: " + trimmed);
            }
            long count = Long.parseLong(bits[0].trim());
            long seconds = Long.parseLong(bits[1].trim());
            if (count <= 0 || seconds <= 0) {
                throw new IllegalArgumentException("Rate limit count/seconds must be positive: " + trimmed);
            }
            windows.add(new Window(count, seconds));
        }
        return List.copyOf(windows);
    }
}
