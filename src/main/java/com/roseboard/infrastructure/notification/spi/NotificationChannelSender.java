package com.roseboard.infrastructure.notification.spi;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ChannelSendRequest;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.model.DeliveryOutcome;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;

public interface NotificationChannelSender {
    ChannelKind kind();

    DeliveryOutcome send(ChannelSendRequest request);

    ConnectivityResult verifyConnectivity(ResolvedChannelConfig config);
}
