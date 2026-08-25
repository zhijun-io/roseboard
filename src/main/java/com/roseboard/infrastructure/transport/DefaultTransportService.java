package com.roseboard.infrastructure.transport;

import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttribute;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.infrastructure.message.ContentType;
import com.roseboard.infrastructure.message.CodecRegistry;
import com.roseboard.infrastructure.message.EncodeRequest;
import com.roseboard.infrastructure.message.MessageEnvelope;
import com.roseboard.infrastructure.queue.spi.QueueCallback;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.consumer.HashPartitionService;
import com.roseboard.infrastructure.transport.cluster.TransportNotificationTopics;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.infrastructure.transport.cluster.TransportSessionRoute;
import com.roseboard.infrastructure.transport.cluster.TransportSessionStore;
import com.roseboard.tenant.profile.TenantCoreIsolationService;
import com.roseboard.tenant.usage.TenantRateLimitService;
import com.roseboard.tenant.usage.TenantUsageService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DefaultTransportService implements TransportService {
    private final DeviceCredentialService credentialsService;
    private final DeviceMapper deviceMapper;
    private final TransportSessionRegistry sessions;
    private final DeviceAttributeService attributeService;
    private final CodecRegistry codecRegistry;
    private final TransportQueueRuntime queueRuntime;
    private final TransportProperties transportProperties;
    private final TenantCoreIsolationService tenantIsolation;
    private final TransportSessionStore sessionStore;
    private final TenantUsageService usageService;
    private final TenantRateLimitService rateLimitService;
    public DefaultTransportService(DeviceCredentialService credentialsService,
                                   DeviceMapper deviceMapper,
                                   TransportSessionRegistry sessions,
                                   DeviceAttributeService attributeService,
                                   CodecRegistry codecRegistry,
                                   TransportQueueRuntime queueRuntime,
                                   TransportProperties transportProperties,
                                   TenantCoreIsolationService tenantIsolation,
                                   TransportSessionStore sessionStore,
                                   TenantUsageService usageService,
                                   TenantRateLimitService rateLimitService) {
        this.credentialsService = credentialsService;
        this.deviceMapper = deviceMapper;
        this.sessions = sessions;
        this.attributeService = attributeService;
        this.codecRegistry = codecRegistry;
        this.queueRuntime = queueRuntime;
        this.transportProperties = transportProperties;
        this.tenantIsolation = tenantIsolation;
        this.sessionStore = sessionStore;
        this.usageService = usageService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public void validateDeviceToken(String deviceToken,
                                    TransportServiceCallback<ValidateDeviceCredentialsResponse> callback) {
        try {
            DeviceCredentialService.DevicePrincipal principal =
                    credentialsService.authenticateAccessToken(deviceToken);
            if (principal == null) {
                callback.onSuccess(new ValidateDeviceCredentialsResponse(null, null));
                return;
            }
            DeviceEntity device = deviceMapper.selectById(principal.deviceId());
            if (device == null || !device.getTenantId().equals(principal.tenantId())) {
                callback.onSuccess(new ValidateDeviceCredentialsResponse(null, null));
                return;
            }
            ValidateDeviceCredentialsResponse.DeviceInfo deviceInfo = new ValidateDeviceCredentialsResponse.DeviceInfo(
                    device.getId(),
                    device.getTenantId(),
                    device.getCustomerId(),
                    device.getDeviceProfileId(),
                    device.getName(),
                    device.getType(),
                    false);
            callback.onSuccess(new ValidateDeviceCredentialsResponse(deviceInfo, deviceToken));
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }
    @Override
    public void validateHttpCredentials(String authorization,
                                        String xAuthorization,
                                        String deviceAccessToken,
                                        String credentialsId,
                                        String credentialsValue,
                                        TransportServiceCallback<ValidateDeviceCredentialsResponse> callback) {
        try {
            DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateHttpCredentials(
                    authorization, xAuthorization, deviceAccessToken, credentialsId, credentialsValue);
            if (principal == null) {
                callback.onSuccess(new ValidateDeviceCredentialsResponse(null, null));
                return;
            }
            DeviceEntity device = deviceMapper.selectById(principal.deviceId());
            if (device == null || !device.getTenantId().equals(principal.tenantId())) {
                callback.onSuccess(new ValidateDeviceCredentialsResponse(null, null));
                return;
            }
            callback.onSuccess(new ValidateDeviceCredentialsResponse(new ValidateDeviceCredentialsResponse.DeviceInfo(
                    device.getId(), device.getTenantId(), device.getCustomerId(), device.getDeviceProfileId(),
                    device.getName(), device.getType(), false), null));
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }

    @Override
    public void process(SessionInfo sessionInfo, PostTelemetryMsg msg, TransportServiceCallback<Void> callback) {
        recordActivity(sessionInfo);
        usageService.beforeTransportMessage(sessionInfo.tenantId(), sessionInfo.deviceId(), 1, rateLimitService);
        sendToMain(sessionInfo.tenantId(), sessionInfo.deviceId(), TransportMessageTypes.TELEMETRY_POST, msg,
                null, Map.of(), callback);
    }

    @Override
    public void process(SessionInfo sessionInfo, PostAttributeMsg msg, TransportServiceCallback<Void> callback) {
        recordActivity(sessionInfo);
        usageService.beforeTransportMessage(sessionInfo.tenantId(), sessionInfo.deviceId(), 1, rateLimitService);
        sendToMain(sessionInfo.tenantId(), sessionInfo.deviceId(), TransportMessageTypes.ATTRIBUTES_POST, msg,
                null, Map.of(), callback);
    }

    @Override
    public void process(SessionInfo sessionInfo, GetAttributeRequestMsg msg, TransportServiceCallback<Void> callback) {
        recordActivity(sessionInfo);
        try {
            GetAttributeResponseMsg response = readAttributes(sessionInfo, msg);
            sessions.notifyGetAttributesResponse(sessionInfo.sessionId(), response);
            callback.onSuccess(null);
        } catch (RuntimeException e) {
            callback.onError(e);
        }
    }

    @Override
    public void process(SessionInfo sessionInfo, SubscribeToAttributeUpdatesMsg msg,
                        TransportServiceCallback<Void> callback) {
        recordActivity(sessionInfo);
        sessions.setSubscribedToAttributes(sessionInfo.sessionId(), !msg.unsubscribe());
        callback.onSuccess(null);
    }

    @Override
    public void process(SessionInfo sessionInfo, ToServerRpcRequestMsg msg, TransportServiceCallback<Void> callback) {
        recordActivity(sessionInfo);
        usageService.beforeTransportMessage(sessionInfo.tenantId(), sessionInfo.deviceId(), 1, rateLimitService);
        sendToMain(sessionInfo.tenantId(), sessionInfo.deviceId(), TransportMessageTypes.TO_SERVER_RPC_POST, msg,
                null, Map.of(), callback);
    }

    @Override
    public void registerSyncSession(SessionInfo sessionInfo, SessionMsgListener listener, long timeout) {
        sessions.register(sessionInfo, listener, Duration.ofMillis(timeout));
    }

    @Override
    public void registerAsyncSession(SessionInfo sessionInfo, SessionMsgListener listener, long idleTimeoutMs) {
        sessions.registerAsync(sessionInfo, listener, Duration.ofMillis(idleTimeoutMs));
    }

    @Override
    public void registerAsyncSession(SessionInfo sessionInfo, SessionMsgListener listener,
                                     long idleTimeoutMs, String protocol) {
        sessions.registerAsync(sessionInfo, listener, Duration.ofMillis(idleTimeoutMs), protocol);
    }

    @Override
    public void deregisterSession(SessionInfo sessionInfo) {
        sessions.closeSession(sessionInfo.sessionId());
    }

    @Override
    public void recordActivity(SessionInfo sessionInfo) {
        sessions.recordActivity(sessionInfo.sessionId());
    }

    void publishUplink(UUID tenantId,
                       UUID deviceId,
                       String messageType,
                       Object value,
                       ContentType contentType,
                       Map<String, String> metadata) {
        sendToMain(tenantId, deviceId, messageType, value, contentType, metadata, TransportServiceCallback.EMPTY);
    }

    void publishDownlink(UUID tenantId, UUID deviceId, TransportToDevicePayload payload) {
        publishNotification(tenantId, deviceId, TransportMessageTypes.TRANSPORT_TO_DEVICE, payload);
    }

    void publishAttributeUpdate(UUID tenantId, UUID deviceId, AttributeUpdateNotificationMsg payload) {
        publishNotification(tenantId, deviceId, TransportMessageTypes.TRANSPORT_ATTRIBUTE_UPDATE, payload);
    }

    void publishToServerRpcResponse(UUID tenantId, UUID deviceId, ToServerRpcResponseMsg payload) {
        publishNotification(tenantId, deviceId, TransportMessageTypes.TRANSPORT_TO_SERVER_RPC_RESPONSE, payload);
    }

    private void publishNotification(UUID tenantId, UUID deviceId, String messageType, Object payload) {
        if (sessions.activeSessionId(deviceId).isEmpty()
                && sessionStore.findByDeviceId(deviceId).isEmpty()) {
            return;
        }
        String nodeId = sessions.activeNodeId(deviceId)
                .filter(id -> !id.isBlank())
                .or(() -> sessionStore.findByDeviceId(deviceId).map(TransportSessionRoute::nodeId))
                .filter(id -> !id.isBlank())
                .orElseGet(() -> transportProperties.getCluster().resolvedNodeId());
        send(
                queueRuntime.transportNotificationsProducer(),
                TransportNotificationTopics.partitionForNode(
                        TransportMessageTypes.TRANSPORT_NOTIFICATIONS_TOPIC, nodeId),
                messageType,
                deviceId.toString(),
                payload,
                null,
                tenantDeviceMetadata(tenantId, deviceId),
                null);
    }

    private void sendToMain(UUID tenantId,
                            UUID deviceId,
                            String messageType,
                            Object value,
                            ContentType contentType,
                            Map<String, String> metadata,
                            TransportServiceCallback<Void> callback) {
        Map<String, String> queueMetadata = tenantDeviceMetadata(tenantId, deviceId);
        if (metadata != null) {
            queueMetadata.putAll(metadata);
        }
        send(queueRuntime.mainProducer(), partitionFor(TransportMessageTypes.MAIN_TOPIC, tenantId, deviceId),
                messageType, deviceId.toString(), value, contentType, queueMetadata, callback);
    }

    private TopicPartitionInfo partitionFor(String topic, UUID tenantId, UUID deviceId) {
        int partitions = transportProperties.getQueue().getPartitions();
        boolean internal = !transportProperties.getQueue().isConsumerPerPartition();
        UUID partitionTenantId = tenantIsolation.isIsolated(tenantId) ? tenantId : null;
        return HashPartitionService.resolve(topic, partitionTenantId, deviceId, partitions, internal);
    }

    private void send(QueueProducer<QueueMessage> producer,
                      TopicPartitionInfo partition,
                      String messageType,
                      String key,
                      Object value,
                      ContentType contentType,
                      Map<String, String> metadata,
                      TransportServiceCallback<Void> callback) {
        EncodeRequest.Builder builder = EncodeRequest.builder()
                .messageId(UUID.randomUUID())
                .key(key)
                .messageType(messageType)
                .schemaId(TransportMessageTypes.schemaId(messageType))
                .schemaVersion(TransportMessageTypes.SCHEMA_VERSION)
                .metadata(metadata)
                .createdAt(System.currentTimeMillis())
                .value(value);
        if (contentType != null) {
            builder.contentType(contentType);
        }
        MessageEnvelope envelope = codecRegistry.encode(builder.build());
        QueueCallback queueCallback = TransportServiceCallback.toQueueCallback(callback);
        producer.send(partition, TransportMessageTypes.toQueueMessage(envelope), queueCallback);
    }

    private GetAttributeResponseMsg readAttributes(SessionInfo sessionInfo, GetAttributeRequestMsg msg) {
        var principal = new DeviceCredentialService.DevicePrincipal(
                sessionInfo.deviceId(), sessionInfo.tenantId(), null, "ACCESS_TOKEN");
        List<KeyValueEntry> client = readScope(principal, AttributeScope.CLIENT, msg.clientAttributeNames());
        List<KeyValueEntry> shared = readScope(principal, AttributeScope.SHARED, msg.sharedAttributeNames());
        return new GetAttributeResponseMsg(msg.requestId(), client, shared);
    }

    private List<KeyValueEntry> readScope(DeviceCredentialService.DevicePrincipal principal,
                                          AttributeScope scope,
                                          List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<AttributeKey> attributeKeys = keys.stream().map(AttributeKey::new).toList();
        List<DeviceAttribute> attributes = attributeService.readFromDevice(principal, scope, attributeKeys);
        List<KeyValueEntry> result = new ArrayList<>(attributes.size());
        for (DeviceAttribute attribute : attributes) {
            result.add(toKeyValue(attribute.key().value(), attribute.value().value()));
        }
        return result;
    }

    private static Map<String, String> tenantDeviceMetadata(UUID tenantId, UUID deviceId) {
        Map<String, String> metadata = new LinkedHashMap<>(2);
        metadata.put("tenantId", tenantId.toString());
        metadata.put("deviceId", deviceId.toString());
        return metadata;
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
