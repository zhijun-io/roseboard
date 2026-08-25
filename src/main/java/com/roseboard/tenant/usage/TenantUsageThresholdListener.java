package com.roseboard.tenant.usage;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.NotificationCenter;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyOptions;
import com.roseboard.infrastructure.notification.model.RecipientRef;
import com.roseboard.notification.catalog.NotificationRecipients;
import com.roseboard.user.UserEntity;
import com.roseboard.user.UserMapper;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TenantUsageThresholdListener {
    private final NotificationCenter notificationCenter;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    public TenantUsageThresholdListener(NotificationCenter notificationCenter,
                                        UserMapper userMapper,
                                        StringRedisTemplate redis) {
        this.notificationCenter = notificationCenter;
        this.userMapper = userMapper;
        this.redis = redis;
    }

    @EventListener
    public void onThreshold(TenantUsageThresholdEvent event) {
        String dedupeKey = "roseboard:usage:notified:"
                + event.tenantId() + ":" + event.period() + ":" + event.metricKey() + ":" + event.status();
        Boolean first = redis.opsForValue().setIfAbsent(dedupeKey, "1", Duration.ofDays(35));
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        List<UserEntity> admins = NotificationRecipients.tenantAdminUsers(userMapper, event.tenantId());
        if (admins.isEmpty()) {
            return;
        }
        List<RecipientRef> recipients = NotificationRecipients.toRecipients(admins);
        notificationCenter.notify(event.tenantId(), new NotifyCommand(
                Set.of(ChannelKind.WEB, ChannelKind.EMAIL),
                recipients,
                "USAGE_" + event.status(),
                "usage.threshold",
                Map.of(
                        "status", event.status(),
                        "metricKey", event.metricKey(),
                        "period", event.period(),
                        "used", event.used(),
                        "limit", event.limit()),
                new NotifyOptions(false, false)));
    }
}
