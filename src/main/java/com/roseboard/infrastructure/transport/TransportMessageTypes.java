package com.roseboard.infrastructure.transport;

import com.roseboard.infrastructure.message.CodecRegistry;
import com.roseboard.infrastructure.message.ContentType;
import com.roseboard.infrastructure.message.MessageDescriptor;
import com.roseboard.infrastructure.message.MessageEnvelope;
import com.roseboard.infrastructure.queue.DefaultQueueMessage;
import com.roseboard.infrastructure.queue.QueueMessage;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TransportMessageTypes {
    public static final String HEADER_MESSAGE_TYPE = "rb.messageType";
    public static final String HEADER_CONTENT_TYPE = "rb.contentType";
    public static final String HEADER_SCHEMA_ID = "rb.schemaId";
    public static final String HEADER_SCHEMA_VERSION = "rb.schemaVersion";
    public static final String HEADER_TENANT_ID = "rb.tenantId";
    public static final String HEADER_DEVICE_ID = "rb.deviceId";

    public static final String MAIN_QUEUE_NAME = "Main";
    public static final String MAIN_TOPIC = "tb_core.main";
    public static final UUID MAIN_QUEUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    public static final String TRANSPORT_NOTIFICATIONS_QUEUE_NAME = "TransportNotifications";
    public static final String TRANSPORT_NOTIFICATIONS_TOPIC = "tb_transport.notifications";
    public static final UUID TRANSPORT_NOTIFICATIONS_QUEUE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000302");

    public static final String TRANSPORT_CORE_CONSUMER_GROUP = "transport-core";
    public static final String TRANSPORT_MAIN_CONSUMER_GROUP = "transport-core-main";

    public static final String TELEMETRY_POST = "telemetry.post";
    public static final String TELEMETRY_SCHEMA_ID = "telemetry.v1";

    public static final String ATTRIBUTES_POST = "attributes.post";
    public static final String ATTRIBUTES_SCHEMA_ID = "attributes.v1";

    public static final String TRANSPORT_TO_DEVICE = "transport.to-device";
    public static final String TRANSPORT_ATTRIBUTE_UPDATE = "transport.attribute-update";
    public static final String TRANSPORT_TO_SERVER_RPC_RESPONSE = "transport.to-server-rpc-response";
    public static final String TO_SERVER_RPC_POST = "to-server-rpc.post";
    public static final String TRANSPORT_SCHEMA_ID = "transport.v1";
    public static final String ATTRIBUTE_UPDATE_SCHEMA_ID = "transport.attribute-update.v1";
    public static final String TO_SERVER_RPC_RESPONSE_SCHEMA_ID = "transport.to-server-rpc-response.v1";
    public static final String TO_SERVER_RPC_SCHEMA_ID = "to-server-rpc.v1";

    public static final int SCHEMA_VERSION = 1;

    private TransportMessageTypes() {
    }

    public static String schemaId(String messageType) {
        return switch (messageType) {
            case TELEMETRY_POST -> TELEMETRY_SCHEMA_ID;
            case ATTRIBUTES_POST -> ATTRIBUTES_SCHEMA_ID;
            case TRANSPORT_TO_DEVICE -> TRANSPORT_SCHEMA_ID;
            case TRANSPORT_ATTRIBUTE_UPDATE -> ATTRIBUTE_UPDATE_SCHEMA_ID;
            case TRANSPORT_TO_SERVER_RPC_RESPONSE -> TO_SERVER_RPC_RESPONSE_SCHEMA_ID;
            case TO_SERVER_RPC_POST -> TO_SERVER_RPC_SCHEMA_ID;
            default -> throw new IllegalArgumentException("Unknown transport messageType: " + messageType);
        };
    }

    public static QueueMessage toQueueMessage(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        Map<String, byte[]> headers = new HashMap<>();
        envelope.headers().forEach(headers::put);
        MessageDescriptor descriptor = envelope.descriptor();
        headers.put(HEADER_MESSAGE_TYPE, utf8(descriptor.messageType()));
        headers.put(HEADER_CONTENT_TYPE, utf8(descriptor.contentType().mediaType()));
        headers.put(HEADER_SCHEMA_ID, utf8(descriptor.schemaId()));
        headers.put(HEADER_SCHEMA_VERSION, utf8(Integer.toString(descriptor.schemaVersion())));
        envelope.metadata().forEach((key, value) -> {
            if ("tenantId".equals(key)) {
                headers.put(HEADER_TENANT_ID, utf8(value));
            } else if ("deviceId".equals(key)) {
                headers.put(HEADER_DEVICE_ID, utf8(value));
            }
        });
        return new DefaultQueueMessage(envelope.key(), envelope.payload(), headers);
    }

    public static MessageDescriptor descriptor(QueueMessage message) {
        Objects.requireNonNull(message, "message");
        String messageType = requiredHeader(message, HEADER_MESSAGE_TYPE);
        ContentType contentType = ContentType.fromMediaType(requiredHeader(message, HEADER_CONTENT_TYPE));
        String schemaId = requiredHeader(message, HEADER_SCHEMA_ID);
        int schemaVersion = Integer.parseInt(requiredHeader(message, HEADER_SCHEMA_VERSION));
        return new MessageDescriptor(
                messageType,
                contentType,
                schemaId,
                schemaVersion,
                com.roseboard.infrastructure.message.MessageLimits.MAX_PAYLOAD_BYTES);
    }

    public static Map<String, String> metadata(QueueMessage message) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(message, HEADER_TENANT_ID, "tenantId", result);
        putIfPresent(message, HEADER_DEVICE_ID, "deviceId", result);
        return Map.copyOf(result);
    }

    public static <T> T decode(CodecRegistry registry, QueueMessage message, Class<T> type) {
        MessageDescriptor descriptor = descriptor(message);
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID())
                .key(message.getKey())
                .headers(message.getHeaders())
                .descriptor(descriptor)
                .createdAt(System.currentTimeMillis())
                .payload(message.getData())
                .build();
        return registry.decode(envelope, type);
    }

    private static void putIfPresent(QueueMessage message, String header, String key, Map<String, String> target) {
        byte[] raw = message.getHeaders().get(header);
        if (raw != null) {
            target.put(key, decodeUtf8(raw));
        }
    }

    private static String requiredHeader(QueueMessage message, String name) {
        byte[] raw = message.getHeaders().get(name);
        if (raw == null) {
            throw new IllegalArgumentException("Missing queue header: " + name);
        }
        return decodeUtf8(raw);
    }

    private static String decodeUtf8(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
