package com.roseboard.infrastructure.transport;

import com.roseboard.device.attribute.AttributePayloadParser;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.telemetry.TelemetryPayloadParser;
import com.roseboard.device.telemetry.TelemetryService;
import com.roseboard.infrastructure.message.CodecRegistry;
import com.roseboard.infrastructure.message.MessageDescriptor;
import com.roseboard.infrastructure.queue.QueueAutoConfiguration;
import com.roseboard.infrastructure.queue.QueueCoordinator;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.infrastructure.queue.spi.QueueProducer;
import com.roseboard.infrastructure.queue.spi.QueueProducerProvider;
import com.roseboard.infrastructure.queue.config.ProcessingStrategy;
import com.roseboard.infrastructure.queue.config.ProcessingStrategyType;
import com.roseboard.infrastructure.queue.config.SubmitStrategy;
import com.roseboard.infrastructure.queue.config.SubmitStrategyType;
import com.roseboard.queue.QueueService;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.transport.cluster.TransportNotificationTopics;
import com.roseboard.infrastructure.cluster.CorePartitionManager;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.roseboard.common.Constants.SYSTEM_TENANT_ID;

@AutoConfiguration(after = QueueAutoConfiguration.class)
public class TransportConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "transportCodecRegistry")
    static CodecRegistry transportCodecRegistry() {
        return CodecRegistry.builder()
                .registerJson(
                        TransportMessageTypes.TELEMETRY_POST,
                        TransportMessageTypes.TELEMETRY_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        PostTelemetryMsg.class)
                .registerProtobuf(
                        TransportMessageTypes.TELEMETRY_POST,
                        TransportMessageTypes.TELEMETRY_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        PostTelemetryMsg.class,
                        PostTelemetryProtobufCodec.INSTANCE)
                .registerJson(
                        TransportMessageTypes.ATTRIBUTES_POST,
                        TransportMessageTypes.ATTRIBUTES_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        PostAttributeMsg.class)
                .registerJson(
                        TransportMessageTypes.TRANSPORT_TO_DEVICE,
                        TransportMessageTypes.TRANSPORT_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        TransportToDevicePayload.class)
                .registerJson(
                        TransportMessageTypes.TRANSPORT_ATTRIBUTE_UPDATE,
                        TransportMessageTypes.ATTRIBUTE_UPDATE_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        AttributeUpdateNotificationMsg.class)
                .registerJson(
                        TransportMessageTypes.TRANSPORT_TO_SERVER_RPC_RESPONSE,
                        TransportMessageTypes.TO_SERVER_RPC_RESPONSE_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        ToServerRpcResponseMsg.class)
                .registerJson(
                        TransportMessageTypes.TO_SERVER_RPC_POST,
                        TransportMessageTypes.TO_SERVER_RPC_SCHEMA_ID,
                        TransportMessageTypes.SCHEMA_VERSION,
                        ToServerRpcRequestMsg.class)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(TransportQueueRuntime.class)
    TransportQueueRuntime transportQueueRuntime(QueueService queueService,
                                                QueueCoordinator coordinator,
                                                CodecRegistry transportCodecRegistry,
                                                TransportSessionRegistry sessions,
                                                TelemetryService telemetryService,
                                                DeviceAttributeService attributeService,
                                                QueueProducerProvider producerProvider,
                                                TransportProperties transportProperties,
                                                CorePartitionManager corePartitionManager,
                                                @Lazy DefaultTransportService transportService) {
        TransportQueueRuntime runtime = new TransportQueueRuntime(
                queueService,
                coordinator,
                transportCodecRegistry,
                sessions,
                telemetryService,
                attributeService,
                producerProvider,
                transportProperties,
                corePartitionManager,
                transportService);
        runtime.initialize();
        return runtime;
    }
}

final class TransportQueueRuntime {
    private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    private final QueueService queueService;
    private final QueueCoordinator coordinator;
    private final CodecRegistry codecRegistry;
    private final TransportSessionRegistry sessions;
    private final TelemetryService telemetryService;
    private final DeviceAttributeService attributeService;
    private final QueueProducerProvider producerProvider;
    private final TransportProperties transportProperties;
    private final CorePartitionManager corePartitionManager;
    private final DefaultTransportService transportService;

    private QueueDefinition mainQueue;
    private QueueDefinition transportNotificationsQueue;
    private QueueProducer<QueueMessage> mainProducer;
    private QueueProducer<QueueMessage> transportNotificationsProducer;

    TransportQueueRuntime(QueueService queueService,
                          QueueCoordinator coordinator,
                          CodecRegistry codecRegistry,
                          TransportSessionRegistry sessions,
                          TelemetryService telemetryService,
                          DeviceAttributeService attributeService,
                          QueueProducerProvider producerProvider,
                          TransportProperties transportProperties,
                          CorePartitionManager corePartitionManager,
                          DefaultTransportService transportService) {
        this.queueService = queueService;
        this.coordinator = coordinator;
        this.codecRegistry = codecRegistry;
        this.sessions = sessions;
        this.telemetryService = telemetryService;
        this.attributeService = attributeService;
        this.producerProvider = producerProvider;
        this.transportProperties = transportProperties;
        this.corePartitionManager = corePartitionManager;
        this.transportService = transportService;
    }

    void initialize() {
        transportNotificationsQueue = ensureQueue(
                TransportMessageTypes.TRANSPORT_NOTIFICATIONS_QUEUE_ID,
                TransportMessageTypes.TRANSPORT_NOTIFICATIONS_QUEUE_NAME,
                TransportMessageTypes.TRANSPORT_NOTIFICATIONS_TOPIC,
                true);
        coordinator.registerBinding(
                TransportMessageTypes.TRANSPORT_NOTIFICATIONS_QUEUE_NAME,
                TransportMessageTypes.TRANSPORT_CORE_CONSUMER_GROUP,
                this::handleDownlink);
        coordinator.startIfBound(transportNotificationsQueue.toTransportConfig());
        subscribeLocalTransportNotifications();

        mainQueue = ensureQueue(
                TransportMessageTypes.MAIN_QUEUE_ID,
                TransportMessageTypes.MAIN_QUEUE_NAME,
                TransportMessageTypes.MAIN_TOPIC,
                false);
        coordinator.registerBinding(
                TransportMessageTypes.MAIN_QUEUE_NAME,
                TransportMessageTypes.TRANSPORT_MAIN_CONSUMER_GROUP,
                this::handleMain);
        coordinator.startIfBound(mainQueue.toTransportConfig());
        applyMainPartitions();

        mainProducer = createProducer(TransportMessageTypes.MAIN_QUEUE_NAME);
        transportNotificationsProducer = createProducer(TransportMessageTypes.TRANSPORT_NOTIFICATIONS_QUEUE_NAME);
    }

    private void subscribeLocalTransportNotifications() {
        String nodeId = transportProperties.getCluster().resolvedNodeId();
        coordinator.findManager(TransportMessageTypes.TRANSPORT_NOTIFICATIONS_QUEUE_NAME).ifPresent(manager ->
                manager.update(Set.of(TransportNotificationTopics.partitionForNode(
                        transportNotificationsQueue.topic(), nodeId))));
    }

    private void applyMainPartitions() {
        if (mainQueue == null) {
            return;
        }
        coordinator.findManager(TransportMessageTypes.MAIN_QUEUE_NAME).ifPresent(manager ->
                manager.update(corePartitionManager.computePartitions(mainQueue.topic())));
    }

    QueueProducer<QueueMessage> mainProducer() {
        return mainProducer;
    }

    QueueProducer<QueueMessage> transportNotificationsProducer() {
        return transportNotificationsProducer;
    }

    QueueDefinition mainQueue() {
        return mainQueue;
    }

    private CompletionStage<Void> handleMain(UUID id, QueueMessage message) {
        MessageDescriptor descriptor = TransportMessageTypes.descriptor(message);
        Map<String, String> metadata = TransportMessageTypes.metadata(message);
        String tenantRaw = metadata.get("tenantId");
        String deviceRaw = metadata.get("deviceId");
        if (tenantRaw == null || deviceRaw == null) {
            return DONE;
        }
        UUID tenantId = UUID.fromString(tenantRaw);
        UUID deviceId = UUID.fromString(deviceRaw);
        return switch (descriptor.messageType()) {
            case TransportMessageTypes.TELEMETRY_POST -> {
                PostTelemetryMsg decoded =
                        TransportMessageTypes.decode(codecRegistry, message, PostTelemetryMsg.class);
                telemetryService.saveBatch(
                        tenantId, deviceId, TelemetryPayloadParser.parseWrites(decoded));
                yield DONE;
            }
            case TransportMessageTypes.ATTRIBUTES_POST -> {
                PostAttributeMsg decoded =
                        TransportMessageTypes.decode(codecRegistry, message, PostAttributeMsg.class);
                DeviceCredentialService.DevicePrincipal principal =
                        new DeviceCredentialService.DevicePrincipal(deviceId, tenantId, null, "ACCESS_TOKEN");
                for (AttributePayloadParser.ClientAttributeWrite write :
                        AttributePayloadParser.parseClientAttributes(decoded)) {
                    attributeService.saveFromDevice(principal, AttributeScope.CLIENT, write.key(), write.value());
                }
                yield DONE;
            }
            case TransportMessageTypes.TO_SERVER_RPC_POST -> {
                ToServerRpcRequestMsg request =
                        TransportMessageTypes.decode(codecRegistry, message, ToServerRpcRequestMsg.class);
                transportService.publishToServerRpcResponse(
                        tenantId,
                        deviceId,
                        new ToServerRpcResponseMsg(
                                request.requestId(),
                                ToServerRpcHandlers.reply(request),
                                null));
                yield DONE;
            }
            default -> DONE;
        };
    }

    private CompletionStage<Void> handleDownlink(UUID id, QueueMessage message) {
        var descriptor = TransportMessageTypes.descriptor(message);
        UUID deviceId = UUID.fromString(message.getKey());
        return switch (descriptor.messageType()) {
            case TransportMessageTypes.TRANSPORT_TO_DEVICE -> {
                TransportToDevicePayload payload = TransportMessageTypes
                        .decode(codecRegistry, message, TransportToDevicePayload.class);
                sessions.deliver(deviceId, payload);
                yield DONE;
            }
            case TransportMessageTypes.TRANSPORT_ATTRIBUTE_UPDATE -> {
                AttributeUpdateNotificationMsg payload = TransportMessageTypes
                        .decode(codecRegistry, message, AttributeUpdateNotificationMsg.class);
                sessions.deliverAttributeUpdate(deviceId, payload);
                yield DONE;
            }
            case TransportMessageTypes.TRANSPORT_TO_SERVER_RPC_RESPONSE -> {
                ToServerRpcResponseMsg payload = TransportMessageTypes
                        .decode(codecRegistry, message, ToServerRpcResponseMsg.class);
                sessions.deliverToServerRpcResponse(deviceId, payload);
                yield DONE;
            }
            default -> DONE;
        };
    }

    private QueueDefinition ensureQueue(UUID id, String name, String topic, boolean perNodeNotifications) {
        return queueService.findOrCreate(queueDraft(id, name, topic, perNodeNotifications));
    }

    private QueueDefinition queueDraft(UUID id, String name, String topic, boolean perNodeNotifications) {
        return QueueDefinition.builder()
                .id(id)
                .tenantId(SYSTEM_TENANT_ID)
                .name(name)
                .topic(topic)
                .partitions(perNodeNotifications ? 1 : transportProperties.getQueue().getPartitions())
                .consumerPerPartition(perNodeNotifications ? false : transportProperties.getQueue().isConsumerPerPartition())
                .pollInterval(25)
                .packProcessingTimeout(5_000)
                .submitStrategy(new SubmitStrategy(SubmitStrategyType.BURST, 0))
                .processingStrategy(new ProcessingStrategy(ProcessingStrategyType.SKIP_ALL_FAILURES, 0, 0, 0, 0))
                .build();
    }

    private QueueProducer<QueueMessage> createProducer(String queueName) {
        QueueDefinition definition = queueService
                .findByName(SYSTEM_TENANT_ID, queueName)
                .orElseThrow(() -> new IllegalStateException("Queue not initialized: " + queueName));
        return producerProvider.createProducer(definition.toTransportConfig());
    }
}
