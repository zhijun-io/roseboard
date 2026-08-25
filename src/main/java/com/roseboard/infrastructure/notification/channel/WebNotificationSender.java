package com.roseboard.infrastructure.notification.channel;

import com.roseboard.infrastructure.notification.model.*;
import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.spi.NotificationInboxWriter;

import java.util.UUID;

public class WebNotificationSender implements NotificationChannelSender {
    private final NotificationInboxWriter inboxWriter;

    public WebNotificationSender(NotificationInboxWriter inboxWriter) {
        this.inboxWriter = inboxWriter;
    }

    @Override
    public ChannelKind kind() {
        return ChannelKind.WEB;
    }

    @Override
    public DeliveryOutcome send(ChannelSendRequest request) {
        RenderedChannelContent rendered = request.rendered();
        if (rendered == null) {
            return DeliveryOutcome.skipped("No rendered WEB content");
        }
        UUID scopeId = request.tenantId();
        if (scopeId == null) {
            return DeliveryOutcome.failed("tenantId is required for WEB delivery");
        }
        int saved = 0;
        for (RecipientRef recipient : request.recipients()) {
            if (recipient.userId() == null) {
                continue;
            }
            inboxWriter.save(scopeId, recipient.userId(), request.notificationType(),
                    rendered.subject(), rendered.body());
            saved++;
        }
        return saved == 0 ? DeliveryOutcome.skipped("No WEB recipients") : DeliveryOutcome.success();
    }

    @Override
    public ConnectivityResult verifyConnectivity(ResolvedChannelConfig config) {
        return new ConnectivityResult(ChannelKind.WEB, ConfigScope.PLATFORM, ConnectivityStatus.OK, null);
    }
}
