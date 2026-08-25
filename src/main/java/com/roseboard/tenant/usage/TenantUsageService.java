package com.roseboard.tenant.usage;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roseboard.customer.CustomerEntity;
import com.roseboard.customer.CustomerMapper;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.tenant.TenantEntity;
import com.roseboard.tenant.TenantMapper;
import com.roseboard.tenant.profile.TenantProfileEntity;
import com.roseboard.tenant.profile.TenantProfileMapper;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TenantUsageService {
    public static final String MAX_DEVICES = "maxDevices";
    public static final String MAX_CUSTOMERS = "maxCustomers";
    public static final String MAX_USERS = "maxUsers";
    public static final String MAX_ASSETS = "maxAssets";
    public static final String MAX_DASHBOARDS = "maxDashboards";
    public static final String MAX_RULE_CHAINS = "maxRuleChains";
    public static final String MAX_RESOURCES_IN_BYTES = "maxResourcesInBytes";
    public static final String MAX_TRANSPORT_MESSAGES = "maxTransportMessages";
    public static final String MAX_TRANSPORT_DATA_POINTS = "maxTransportDataPoints";
    public static final String MAX_EMAILS = "maxEmails";
    public static final String MAX_SMS = "maxSms";
    public static final String MAX_OTA_BYTES = "maxOtaPackagesInBytes";

    private static final List<String> PERIOD_KEYS = List.of(
            MAX_TRANSPORT_MESSAGES, MAX_TRANSPORT_DATA_POINTS, MAX_EMAILS, MAX_SMS, MAX_OTA_BYTES);

    private final TenantMapper tenantMapper;
    private final TenantProfileMapper tenantProfileMapper;
    private final DeviceMapper deviceMapper;
    private final CustomerMapper customerMapper;
    private final UserMapper userMapper;
    private final TenantUsageCounterMapper counterMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock = Clock.systemUTC();

    public TenantUsageService(TenantMapper tenantMapper, TenantProfileMapper tenantProfileMapper,
                              DeviceMapper deviceMapper, CustomerMapper customerMapper,
                              UserMapper userMapper, TenantUsageCounterMapper counterMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.tenantMapper = tenantMapper;
        this.tenantProfileMapper = tenantProfileMapper;
        this.deviceMapper = deviceMapper;
        this.customerMapper = customerMapper;
        this.userMapper = userMapper;
        this.counterMapper = counterMapper;
        this.eventPublisher = eventPublisher;
    }

    public TenantUsageReport report(UUID tenantId) {
        requireTenant(tenantId);
        JsonNode configuration = configuration(tenantId);
        String period = currentPeriod();
        double warnThreshold = warnThreshold(configuration);
        List<TenantUsageItem> items = new ArrayList<>();
        items.add(item(MAX_DEVICES, countDevices(tenantId), limit(configuration, MAX_DEVICES), warnThreshold, false));
        items.add(item(MAX_CUSTOMERS, countCustomers(tenantId), limit(configuration, MAX_CUSTOMERS), warnThreshold, false));
        items.add(item(MAX_USERS, countUsers(tenantId), limit(configuration, MAX_USERS), warnThreshold, false));
        boolean smsDisabled = isSmsDisabled(configuration);
        for (String key : PERIOD_KEYS) {
            long used = counterUsed(tenantId, period, key);
            items.add(item(key, used, limit(configuration, key), warnThreshold, key.equals(MAX_SMS) && smsDisabled));
        }
        return new TenantUsageReport(tenantId, period, items);
    }

    @Transactional(rollbackFor = Exception.class)
    public void increment(UUID tenantId, String metricKey, long delta) {
        if (delta == 0) {
            return;
        }
        if (delta < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usage increment must be non-negative");
        }
        requireTenant(tenantId);
        if (!PERIOD_KEYS.contains(metricKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown usage metric: " + metricKey);
        }
        JsonNode configuration = configuration(tenantId);
        String period = currentPeriod();
        long after = counterMapper.incrementAndSelect(tenantId, period, metricKey, delta);
        long before = after - delta;
        publishThresholdIfEntered(tenantId, period, metricKey, before, after, configuration);
    }

    public void requireEntityQuota(UUID tenantId, String metricKey) {
        JsonNode configuration = configuration(tenantId);
        long limit = limit(configuration, metricKey);
        if (limit <= 0) {
            return;
        }
        long used = switch (metricKey) {
            case MAX_DEVICES -> countDevices(tenantId);
            case MAX_CUSTOMERS -> countCustomers(tenantId);
            case MAX_USERS -> countUsers(tenantId);
            default -> throw new IllegalArgumentException("Not an entity quota key: " + metricKey);
        };
        if (used >= limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tenant package limit reached for " + metricKey + " (" + used + "/" + limit + ")");
        }
    }

    public void requirePeriodQuota(UUID tenantId, String metricKey, long delta) {
        if (delta <= 0) {
            return;
        }
        JsonNode configuration = configuration(tenantId);
        if (metricKey.equals(MAX_SMS) && isSmsDisabled(configuration)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SMS is disabled for tenant package");
        }
        long limit = limit(configuration, metricKey);
        if (limit <= 0) {
            return;
        }
        long used = counterUsed(tenantId, currentPeriod(), metricKey);
        if (used + delta > limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tenant package limit reached for " + metricKey + " (" + used + "/" + limit + ")");
        }
    }

    public void beforeTransportWrite(UUID tenantId, UUID deviceId, int messages, int dataPoints,
                                     TenantRateLimitService rateLimitService) {
        if (messages <= 0 || dataPoints <= 0) {
            return;
        }
        rateLimitService.consumeTenant(tenantId, TenantRateLimitService.TRANSPORT_TENANT_TELEMETRY_MSG, messages);
        rateLimitService.consumeTenant(tenantId, TenantRateLimitService.TRANSPORT_TENANT_TELEMETRY_DP, dataPoints);
        rateLimitService.consumeDevice(tenantId, deviceId, TenantRateLimitService.TRANSPORT_DEVICE_TELEMETRY_MSG,
                messages);
        rateLimitService.consumeDevice(tenantId, deviceId, TenantRateLimitService.TRANSPORT_DEVICE_TELEMETRY_DP,
                dataPoints);
        requirePeriodQuota(tenantId, MAX_TRANSPORT_MESSAGES, messages);
        requirePeriodQuota(tenantId, MAX_TRANSPORT_DATA_POINTS, dataPoints);
    }

    /** Enforces TB transport message limits before an adapter enqueues a message. */
    public void beforeTransportMessage(UUID tenantId, UUID deviceId, int messages,
                                       TenantRateLimitService rateLimitService) {
        if (messages <= 0) {
            return;
        }
        rateLimitService.consumeTenant(tenantId, TenantRateLimitService.TRANSPORT_TENANT_MSG, messages);
        rateLimitService.consumeDevice(tenantId, deviceId, TenantRateLimitService.TRANSPORT_DEVICE_MSG, messages);
        requirePeriodQuota(tenantId, MAX_TRANSPORT_MESSAGES, messages);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordTransportWrite(UUID tenantId, int messages, int dataPoints) {
        if (messages > 0) {
            increment(tenantId, MAX_TRANSPORT_MESSAGES, messages);
        }
        if (dataPoints > 0) {
            increment(tenantId, MAX_TRANSPORT_DATA_POINTS, dataPoints);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordEmail(UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        requirePeriodQuota(tenantId, MAX_EMAILS, 1);
        increment(tenantId, MAX_EMAILS, 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordSms(UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        requirePeriodQuota(tenantId, MAX_SMS, 1);
        increment(tenantId, MAX_SMS, 1);
    }

    public String rateLimitConfiguration(UUID tenantId, String key) {
        JsonNode configuration = configuration(tenantId);
        if (configuration == null || !configuration.has(key) || configuration.get(key).isNull()) {
            return null;
        }
        String value = configuration.get(key).asText("");
        return StringUtils.hasText(value) ? value : null;
    }

    public long profileConfigurationLong(UUID tenantId, String key) {
        return limit(configuration(tenantId), key);
    }

    public String currentPeriod() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private void publishThresholdIfEntered(UUID tenantId, String period, String metricKey,
                                           long before, long after, JsonNode configuration) {
        long limit = limit(configuration, metricKey);
        double warn = warnThreshold(configuration);
        boolean smsDisabled = metricKey.equals(MAX_SMS) && isSmsDisabled(configuration);
        String beforeStatus = statusOf(before, limit, warn, smsDisabled);
        String afterStatus = statusOf(after, limit, warn, smsDisabled);
        if (beforeStatus.equals(afterStatus)) {
            return;
        }
        if ("WARNING".equals(afterStatus) || "EXCEEDED".equals(afterStatus)) {
            eventPublisher.publishEvent(new TenantUsageThresholdEvent(
                    tenantId, period, metricKey, afterStatus, after, limit, warn));
        }
    }

    private TenantUsageItem item(String key, long used, long limit, double warnThreshold, boolean disabled) {
        return new TenantUsageItem(key, used, limit, warnThreshold, statusOf(used, limit, warnThreshold, disabled));
    }

    private static String statusOf(long used, long limit, double warnThreshold, boolean disabled) {
        if (disabled) {
            return "DISABLED";
        }
        if (limit <= 0) {
            return "UNLIMITED";
        }
        if (used >= limit) {
            return "EXCEEDED";
        }
        if (used >= Math.ceil(limit * warnThreshold)) {
            return "WARNING";
        }
        return "OK";
    }

    private JsonNode configuration(UUID tenantId) {
        TenantEntity tenant = requireTenant(tenantId);
        TenantProfileEntity profile = tenantProfileMapper.selectById(tenant.getTenantProfileId());
        if (profile == null || profile.getProfileData() == null) {
            return null;
        }
        JsonNode configuration = profile.getProfileData().get("configuration");
        return configuration != null && configuration.isObject() ? configuration : null;
    }

    private TenantEntity requireTenant(UUID tenantId) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        return tenant;
    }

    private static long limit(JsonNode configuration, String key) {
        if (configuration == null || !configuration.has(key) || configuration.get(key).isNull()) {
            return 0L;
        }
        return configuration.get(key).asLong(0L);
    }

    private static double warnThreshold(JsonNode configuration) {
        if (configuration == null || !configuration.has("warnThreshold")
                || configuration.get("warnThreshold").isNull()) {
            return 0.8d;
        }
        double value = configuration.get("warnThreshold").asDouble(0.8d);
        return value <= 0d ? 0.8d : value;
    }

    private static boolean isSmsDisabled(JsonNode configuration) {
        return configuration != null && configuration.path("smsEnabled").isBoolean()
                && !configuration.path("smsEnabled").asBoolean();
    }

    private long countDevices(UUID tenantId) {
        return deviceMapper.selectCount(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getTenantId, tenantId));
    }

    private long countCustomers(UUID tenantId) {
        return customerMapper.selectCount(new LambdaQueryWrapper<CustomerEntity>()
                .eq(CustomerEntity::getTenantId, tenantId));
    }

    private long countUsers(UUID tenantId) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getTenantId, tenantId));
    }

    private long counterUsed(UUID tenantId, String period, String key) {
        Long used = counterMapper.selectUsed(tenantId, period, key);
        return used == null ? 0L : used;
    }
}
