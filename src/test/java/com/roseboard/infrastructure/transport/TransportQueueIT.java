package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialService.DevicePrincipal;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.infrastructure.message.ContentType;
import com.roseboard.infrastructure.message.CodecRegistry;
import com.roseboard.infrastructure.message.MessageDescriptor;
import com.roseboard.infrastructure.queue.QueueMessage;
import com.roseboard.queue.QueueDefinition;
import com.roseboard.infrastructure.queue.QueueCoordinator;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueConsumer;
import com.roseboard.infrastructure.queue.adapter.memory.InMemoryQueueStorage;
import com.roseboard.infrastructure.queue.TopicPartitionInfo;
import com.roseboard.infrastructure.queue.consumer.HashPartitionService;
import com.roseboard.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransportQueueIT extends TransportIntegrationTestBase {
    @Container
    static final PostgreSQLContainer<?> postgres = IntegrationTestBase.postgres();
    @Container
    static final GenericContainer<?> valkey = IntegrationTestBase.valkey();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestBase.registerProperties(registry, postgres, valkey);
        registry.add("roseboard.queue.provider", () -> "memory");
        registry.add("roseboard.transport.mqtt.enabled", () -> "false");
    }

    @Autowired DefaultTransportService transportService;
    @Autowired TransportSessionRegistry sessions;
    @Autowired TransportQueueRuntime queueRuntime;
    @Autowired QueueCoordinator coordinator;
    @Autowired CodecRegistry transportCodecRegistry;
    @Autowired InMemoryQueueStorage queueStorage;
    @Autowired
    DeviceCredentialService credentialsService;

    private UUID deviceId;
    private DevicePrincipal principal;

    @BeforeEach
    void seed() {
        seedTenant("transport-queue-tp-");
        deviceId = createDevice("queue-device");
        String token = "queue-token-" + deviceId;
        credentialsService.save(tenantId, deviceId, DeviceCredentialType.ACCESS_TOKEN, token, null);
        principal = credentialsService.authenticateAccessToken(token);
    }

    @Test
    void publishUplinkEnqueuesDefaultJsonTelemetryPost() throws Exception {
        coordinator.stopIfBound(TransportMessageTypes.MAIN_QUEUE_NAME);
        transportService.publishUplink(
                tenantId,
                deviceId,
                TransportMessageTypes.TELEMETRY_POST,
                PostTelemetryProtobufCodec.sample(21L, "celsius"),
                null,
                Map.of());

        QueueMessage message = awaitMainQueueMessage();
        assertEquals(deviceId.toString(), message.getKey());

        MessageDescriptor descriptor = TransportMessageTypes.descriptor(message);
        assertEquals(ContentType.APPLICATION_JSON, descriptor.contentType());
        assertEquals(TransportMessageTypes.TELEMETRY_POST, descriptor.messageType());

         PostTelemetryMsg decoded =
                TransportMessageTypes.decode(transportCodecRegistry, message, PostTelemetryMsg.class);
        assertEquals(21L, decoded.tsKvList().getFirst().kv().stream()
                .filter(entry -> "value".equals(entry.key()))
                .findFirst()
                .orElseThrow()
                .longV());
        assertEquals(tenantId.toString(), TransportMessageTypes.metadata(message).get("tenantId"));
    }

    @Test
    void publishUplinkSupportsProtobufTelemetryPost() throws Exception {
        coordinator.stopIfBound(TransportMessageTypes.MAIN_QUEUE_NAME);
        transportService.publishUplink(
                tenantId,
                deviceId,
                TransportMessageTypes.TELEMETRY_POST,
                PostTelemetryProtobufCodec.sample(7L, "f"),
                ContentType.APPLICATION_X_PROTOBUF,
                Map.of());

        QueueMessage message = awaitMainQueueMessage();
        MessageDescriptor descriptor = TransportMessageTypes.descriptor(message);
        assertEquals(ContentType.APPLICATION_X_PROTOBUF, descriptor.contentType());

         PostTelemetryMsg decoded =
                TransportMessageTypes.decode(transportCodecRegistry, message, PostTelemetryMsg.class);
        assertEquals(7L, decoded.tsKvList().getFirst().kv().stream()
                .filter(entry -> "value".equals(entry.key()))
                .findFirst()
                .orElseThrow()
                .longV());
    }

    @Test
    void downlinkQueueDeliversToRegisteredSession() throws Exception {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        sessions.register(SessionInfo.fromPrincipal(principal), received::set, Duration.ofSeconds(30));

        ObjectNode params = JacksonUtils.newObjectNode();
        params.put("x", 1);
        transportService.publishDownlink(
                tenantId,
                deviceId,
                new TransportToDevicePayload("ping", params, 3));

        await(() -> received.get() != null, 5);
        TransportToDevicePayload message = received.get();
        assertEquals("ping", message.method());
        assertEquals(3, message.requestId());

        TransportToDevicePayload secondDownlink = new TransportToDevicePayload("noop", JacksonUtils.newObjectNode(), 4);
        assertEquals(DeliverResult.NO_SESSION, sessions.deliver(deviceId, secondDownlink));
    }

    @Test
    void downlinkQueueDoesNotDeliverWithoutSession() throws Exception {
        AtomicReference<TransportToDevicePayload> received = new AtomicReference<>();
        transportService.publishDownlink(
                tenantId,
                deviceId,
                new TransportToDevicePayload("ping", JacksonUtils.newObjectNode(), 1));

        Thread.sleep(300);
        assertNull(received.get());
        assertEquals(DeliverResult.NO_SESSION, sessions.deliver(
                deviceId,
                new TransportToDevicePayload("ping", JacksonUtils.newObjectNode(), 1)));
    }

    private QueueMessage awaitMainQueueMessage() throws InterruptedException {
        AtomicReference<QueueMessage> captured = new AtomicReference<>();
        await(() -> {
            for (QueueMessage message : pollMainQueue()) {
                if (deviceId.toString().equals(message.getKey())) {
                    captured.set(message);
                    return true;
                }
            }
            return false;
        }, 5);
        return captured.get();
    }

    private List<QueueMessage> pollMainQueue() {
        QueueDefinition definition = queueRuntime.mainQueue();
        TopicPartitionInfo partition = HashPartitionService.resolve(
                definition.topic(),
                null,
                deviceId.toString(),
                definition.partitions(),
                false);
        InMemoryQueueConsumer consumer = new InMemoryQueueConsumer(
                queueStorage, definition.toTransportConfig(), "transport-uplink-test-" + UUID.randomUUID());
        consumer.subscribe(Set.of(partition));
        return consumer.poll(10);
    }
}
