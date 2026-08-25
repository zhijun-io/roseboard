package com.roseboard.infrastructure.notification;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ConnectivityResult;
import com.roseboard.infrastructure.notification.model.NotifyCommand;
import com.roseboard.infrastructure.notification.model.NotifyResult;
import java.util.List;

import java.util.UUID;

public interface NotificationCenter {
    NotifyResult notify(UUID tenantId, NotifyCommand command);


    ConnectivityResult verifyConnectivity(UUID tenantId, ChannelKind kind);

    List<ChannelKind> availableChannels(UUID tenantId);
}
