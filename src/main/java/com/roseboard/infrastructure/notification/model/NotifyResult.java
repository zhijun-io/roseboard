package com.roseboard.infrastructure.notification.model;

import java.util.Map;

public record NotifyResult(Map<ChannelKind, DeliveryOutcome> byChannel) {
}
