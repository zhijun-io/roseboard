package com.roseboard.infrastructure.notification.channel;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ChannelSendRequest;
import com.roseboard.infrastructure.notification.model.ConfigScope;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.model.ConnectivityStatus;
import com.roseboard.infrastructure.notification.model.DeliveryOutcome;
import com.roseboard.infrastructure.notification.model.RenderedChannelContent;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import com.roseboard.infrastructure.notification.internal.RecipientDelivery;
import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.spi.NotificationUsageMeter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

public class SmsNotificationSender implements NotificationChannelSender {
    private final ObjectMapper objectMapper;
    private final ObjectProvider<NotificationUsageMeter> usageMeter;

    public SmsNotificationSender(ObjectMapper objectMapper, ObjectProvider<NotificationUsageMeter> usageMeter) {
        this.objectMapper = objectMapper;
        this.usageMeter = usageMeter;
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.SMS;
    }

    @Override
    public DeliveryOutcome send(ChannelSendRequest request) {
        RenderedChannelContent rendered = request.rendered();
        if (rendered == null) {
            return DeliveryOutcome.skipped("No rendered SMS content");
        }
        ResolvedChannelConfig config = request.channelConfig();
        if (!config.isComplete()) {
            return DeliveryOutcome.notConfigured("SMS is not configured");
        }
        HttpSmsClient sender = new HttpSmsClient(toSmsSettings(config));
        return RecipientDelivery.deliver(
                request.recipients(),
                recipient -> StringUtils.hasText(recipient.phone()),
                recipient -> {
                    sender.sendSms(recipient.phone(), rendered.body());
                    if (request.countUsage()) {
                        recordUsage(request.tenantId());
                    }
                },
                "No SMS recipients");
    }

    @Override
    public ConnectivityResult verifyConnectivity(ResolvedChannelConfig config) {
        if (config == null || !config.enabled()) {
            return new ConnectivityResult(ChannelKind.SMS, config == null ? ConfigScope.PLATFORM
                    : config.scope(), ConnectivityStatus.DISABLED, null);
        }
        if (!config.isComplete()) {
            return new ConnectivityResult(ChannelKind.SMS, config.scope(),
                    ConnectivityStatus.NOT_CONFIGURED, "SMS is not configured");
        }
        try {
            new HttpSmsClient(toSmsSettings(config));
            return new ConnectivityResult(ChannelKind.SMS, config.scope(), ConnectivityStatus.OK, null);
        } catch (RuntimeException exception) {
            return new ConnectivityResult(ChannelKind.SMS, config.scope(), ConnectivityStatus.FAILED,
                    exception.getMessage());
        }
    }

    private ObjectNode toSmsSettings(ResolvedChannelConfig config) {
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("baseUrl", config.configString("baseUrl"));
        settings.put("apiKey", config.secretString("apiKey"));
        return settings;
    }

    private void recordUsage(UUID tenantId) {
        NotificationUsageMeter usage = usageMeter.getIfAvailable();
        if (tenantId != null && usage != null) {
            usage.record(tenantId, ChannelKind.SMS);
        }
    }
}
