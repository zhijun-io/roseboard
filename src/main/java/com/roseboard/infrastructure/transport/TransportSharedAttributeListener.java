package com.roseboard.infrastructure.transport;

import com.roseboard.device.attribute.SharedAttributeChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
class TransportSharedAttributeListener {
    private final DefaultTransportService transportService;

    TransportSharedAttributeListener(DefaultTransportService transportService) {
        this.transportService = transportService;
    }

    @EventListener
    void onSharedAttributeChanged(SharedAttributeChangedEvent event) {
        List<KeyValueEntry> updated = new ArrayList<>();
        for (Map.Entry<String, Object> entry : event.updated().entrySet()) {
            updated.add(toKeyValue(entry.getKey(), entry.getValue()));
        }
        List<String> deleted = event.deleted() == null ? List.of() : event.deleted();
        if (updated.isEmpty() && deleted.isEmpty()) {
            return;
        }
        transportService.publishAttributeUpdate(
                event.tenantId(),
                event.deviceId(),
                new AttributeUpdateNotificationMsg(updated, deleted));
    }

    private static KeyValueEntry toKeyValue(String key, Object value) {
        return switch (value) {
            case Boolean bool -> new KeyValueEntry(key, KeyValueType.BOOLEAN_V, bool, null, 0L, 0D, null);
            case Integer i -> new KeyValueEntry(key, KeyValueType.LONG_V, false, null, i.longValue(), 0D, null);
            case Long l -> new KeyValueEntry(key, KeyValueType.LONG_V, false, null, l, 0D, null);
            case Double d -> new KeyValueEntry(key, KeyValueType.DOUBLE_V, false, null, 0L, d, null);
            case Float f -> new KeyValueEntry(key, KeyValueType.DOUBLE_V, false, null, 0L, f.doubleValue(), null);
            case String s -> new KeyValueEntry(key, KeyValueType.STRING_V, false, s, 0L, 0D, null);
            case null -> new KeyValueEntry(key, KeyValueType.STRING_V, false, null, 0L, 0D, null);
            default -> new KeyValueEntry(key, KeyValueType.JSON_V, false, null, 0L, 0D,
                    com.roseboard.common.JacksonUtils.writeValueAsString(value));
        };
    }
}
