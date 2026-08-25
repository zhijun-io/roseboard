package com.roseboard.infrastructure.transport.mqtt;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.infrastructure.audit.AuditContext;
import com.roseboard.infrastructure.audit.event.AuditOrigin;
import com.roseboard.infrastructure.audit.context.AuditContextHolder;
import com.roseboard.infrastructure.transport.GetAttributeRequestMsg;
import com.roseboard.infrastructure.transport.SessionInfo;
import com.roseboard.infrastructure.transport.SubscribeToAttributeUpdatesMsg;
import com.roseboard.infrastructure.transport.ToServerRpcRequestMsg;
import com.roseboard.infrastructure.transport.TransportDeviceApiService;
import com.roseboard.infrastructure.transport.TransportRpcService;
import com.roseboard.infrastructure.transport.TransportService;
import com.roseboard.infrastructure.transport.TransportServiceCallback;
import com.roseboard.infrastructure.transport.TransportPayloadDecoder;
import com.roseboard.infrastructure.transport.cluster.TransportProperties;
import com.roseboard.infrastructure.transport.X509CertificateUtil;
import com.roseboard.ota.OtaPackageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPubReplyMessageVariableHeader;
import io.netty.handler.codec.mqtt.MqttReasonCodes;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ChannelHandler.Sharable
@Component
@ConditionalOnProperty(prefix = "roseboard.transport.mqtt", name = "enabled", havingValue = "true")
public class MqttTransportHandler extends SimpleChannelInboundHandler<MqttMessage> {
    private static final Logger log = LoggerFactory.getLogger(MqttTransportHandler.class);
    private static final Pattern FW_REQUEST = Pattern.compile(
            "^" + Pattern.quote(MqttTopics.Ota.FIRMWARE_REQUEST_PREFIX) + "(\\d+)/chunk/(\\d+)$");
    private static final Pattern SW_REQUEST = Pattern.compile(
            "^" + Pattern.quote(MqttTopics.Ota.SOFTWARE_REQUEST_PREFIX) + "(\\d+)/chunk/(\\d+)$");

    private final DeviceCredentialService credentialsService;
    private final int maximumPacketSize;
    private final int serverReceiveMaximum;
    private final DeviceMapper deviceMapper;
    private final TransportService transportService;
    private final TransportRpcService transportRpcService;
    private final TransportDeviceApiService deviceApiService;
    private final long idleTimeoutMs;
    private final boolean skipValidityCheckForClientCert;
    private final TransportProperties transportProperties;

    public MqttTransportHandler(DeviceCredentialService credentialsService,
                                DeviceMapper deviceMapper,
                                TransportService transportService,
                                TransportRpcService transportRpcService,
                                TransportDeviceApiService deviceApiService,
                                MqttTransportProperties properties,
                                TransportProperties transportProperties) {
        this.credentialsService = credentialsService;
        this.maximumPacketSize = Math.max(1, properties.getMaximumPacketSize());
        this.serverReceiveMaximum = Math.max(1, Math.min(65_535, properties.getServerReceiveMaximum()));
        this.deviceMapper = deviceMapper;
        this.transportService = transportService;
        this.transportRpcService = transportRpcService;
        this.deviceApiService = deviceApiService;
        this.idleTimeoutMs = properties.getIdleTimeoutMs();
        this.skipValidityCheckForClientCert = properties.getSsl().isSkipValidityCheckForClientCert();
        this.transportProperties = transportProperties;
    }
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
        switch (msg.fixedHeader().messageType()) {
            case CONNECT -> handleConnect(ctx, (MqttConnectMessage) msg);
            case PUBLISH -> handlePublish(ctx, (MqttPublishMessage) msg);
            case PUBREL -> handlePubRel(ctx, msg);
            case PUBACK -> handlePubAck(ctx, msg);
            case PUBREC -> handlePubRec(ctx, msg);
            case PUBCOMP -> handlePubComp(ctx, msg);
            case SUBSCRIBE -> handleSubscribe(ctx, (MqttSubscribeMessage) msg);
            case UNSUBSCRIBE -> handleUnsubscribe(ctx, msg);
            case PINGREQ, DISCONNECT -> handleSimple(ctx, msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        MqttConnection connection = connection(ctx.channel());
        if (connection != null && connection.sessionInfo() != null) {
            transportService.deregisterSession(connection.sessionInfo());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("MQTT channel error: {}", cause.getMessage());
        ctx.close();
    }

    private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        boolean mqtt5 = msg.variableHeader().version() == 5;
        MqttProperties connectProperties = msg.variableHeader().properties();
        int clientReceiveMaximum = propertyInt(connectProperties, MqttProperties.RECEIVE_MAXIMUM, 65_535);
        int clientMaximumPacketSize = propertyInt(connectProperties, MqttProperties.MAXIMUM_PACKET_SIZE,
                maximumPacketSize);
        String clientId = msg.payload().clientIdentifier();
        String username = msg.payload().userName();
        String password = password(msg);

        if (isProvisionConnection(clientId, username)) {
            MqttSessionListener listener = new MqttSessionListener(
                    ctx.channel(), clientReceiveMaximum, clientMaximumPacketSize);
            setConnection(ctx.channel(), new MqttConnection(
                    null, listener, true, mqtt5, ConcurrentHashMap.newKeySet()));
            ctx.writeAndFlush(connAck(MqttConnectReturnCode.CONNECTION_ACCEPTED, mqtt5,
                    serverReceiveMaximum, maximumPacketSize));
            return;
        }

        DeviceCredentialService.DevicePrincipal principal = authenticate(ctx, clientId, username, password);
        if (principal == null) {
            ctx.writeAndFlush(connAck(mqtt5
                    ? MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD
                    : MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD, mqtt5,
                    serverReceiveMaximum, maximumPacketSize));
            ctx.close();
            return;
        }

        DeviceEntity device = deviceMapper.selectById(principal.deviceId());
        if (device == null || !device.getTenantId().equals(principal.tenantId())) {
            ctx.writeAndFlush(connAck(mqtt5
                    ? MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED_5
                    : MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED, mqtt5,
                    serverReceiveMaximum, maximumPacketSize));
            ctx.close();
            return;
        }

        long now = System.currentTimeMillis();
        SessionInfo sessionInfo = new SessionInfo(
                UUID.randomUUID(),
                device.getId(),
                device.getTenantId(),
                device.getCustomerId(),
                device.getDeviceProfileId(),
                device.getName(),
                device.getType(),
                false,
                transportProperties.getCluster().resolvedNodeId(),
                now,
                now);
        MqttSessionListener listener = new MqttSessionListener(
                ctx.channel(), clientReceiveMaximum, clientMaximumPacketSize);
        transportService.registerAsyncSession(sessionInfo, listener, idleTimeoutMs, "MQTT");
        setConnection(ctx.channel(), new MqttConnection(
                sessionInfo, listener, false, mqtt5, ConcurrentHashMap.newKeySet()));
        ctx.writeAndFlush(connAck(MqttConnectReturnCode.CONNECTION_ACCEPTED, mqtt5,
                serverReceiveMaximum, maximumPacketSize));
    }

    private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection == null) {
            return;
        }
        int packetId = msg.variableHeader().packetId();
        if (msg.fixedHeader().qosLevel() == MqttQoS.EXACTLY_ONCE
                && !connection.qos2Inbound().add(packetId)) {
            writePubReply(ctx, connection, MqttMessageType.PUBREC, packetId, reasonSuccess());
            return;
        }
        String topic = msg.variableHeader().topicName();
        byte[] payloadBytes = payloadBytes(msg.payload());
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        if (connection.provisionOnly()) {
            if (MqttTopics.Provision.REQUEST.equals(topic)) {
                handleProvision(connection, payload);
                ackPublish(ctx, msg);
            } else {
                ackPublish(ctx, msg, MqttReasonCodes.PubAck.TOPIC_NAME_INVALID.byteValue());
            }
            return;
        }

        if (connection.sessionInfo() == null) {
            ackPublish(ctx, msg, MqttReasonCodes.PubAck.NOT_AUTHORIZED.byteValue());
            return;
        }

        SessionInfo session = connection.sessionInfo();
        transportService.recordActivity(session);
        try {
            AuditContextHolder.run(mqttAuditContext(session), () ->
                    processDevicePublish(connection, ctx, msg, session, topic, payloadBytes, payload));
        } catch (RuntimeException exception) {
            log.debug("[{}] Failed MQTT publish on {}: {}", session.sessionId(), topic, exception.getMessage());
            ackPublish(ctx, msg, reasonFor(exception));
        }
    }

    private void processDevicePublish(MqttConnection connection, ChannelHandlerContext ctx, MqttPublishMessage msg,
                                    SessionInfo session, String topic, byte[] payloadBytes, String payload) {
        if (MqttTopics.Device.TELEMETRY.equals(topic)) {
            transportService.process(session,
                    TransportPayloadDecoder.telemetry(payloadBytes, contentType(msg.variableHeader().properties())),
                    noopCallback(ctx, msg));
        } else if (MqttTopics.Device.ATTRIBUTES.equals(topic)) {
            transportService.process(session,
                    TransportPayloadDecoder.attributes(payloadBytes, contentType(msg.variableHeader().properties())),
                    noopCallback(ctx, msg));
        } else if (topic.startsWith(MqttTopics.Device.ATTRIBUTES_REQUEST_PREFIX)) {
            int requestId = suffixInt(topic, MqttTopics.Device.ATTRIBUTES_REQUEST_PREFIX);
            transportService.process(session, parseAttributeRequest(requestId, payload), noopCallback(ctx, msg));
        } else if (topic.startsWith(MqttTopics.Device.RPC_RESPONSE_PREFIX)) {
            int requestId = suffixInt(topic, MqttTopics.Device.RPC_RESPONSE_PREFIX);
            transportRpcService.completeDeviceRpcReply(session, requestId, payload);
            ackPublish(ctx, msg);
        } else if (topic.startsWith(MqttTopics.Device.RPC_REQUEST_PREFIX)) {
            int requestId = suffixInt(topic, MqttTopics.Device.RPC_REQUEST_PREFIX);
            JsonNode body = JacksonUtils.toJsonNode(payload);
            if (body == null || !body.hasNonNull("method") || !body.has("params")) {
                ackPublish(ctx, msg, MqttReasonCodes.PubAck.PAYLOAD_FORMAT_INVALID.byteValue());
                return;
            }
            transportService.process(session,
                    new ToServerRpcRequestMsg(requestId, body.get("method").asText(), body.get("params")),
                    noopCallback(ctx, msg));
        } else if (MqttTopics.Device.CLAIM.equals(topic)) {
            deviceApiService.registerClaimingInfo(session.tenantId(), session.deviceId(), payload);
            ackPublish(ctx, msg);
        } else if (handleOtaPublish(connection, topic, payload)) {
            ackPublish(ctx, msg);
        } else {
            ackPublish(ctx, msg, MqttReasonCodes.PubAck.TOPIC_NAME_INVALID.byteValue());
        }
    }

    private static AuditContext mqttAuditContext(SessionInfo session) {
        return AuditContext.of(AuditOrigin.MQTT, session.sessionId().toString(), java.util.Map.of(
                "deviceId", session.deviceId().toString(),
                "tenantId", session.tenantId().toString()));
    }

    private boolean handleOtaPublish(MqttConnection connection, String topic, String payload) {
        Matcher fw = FW_REQUEST.matcher(topic);
        if (fw.matches()) {
            return publishOtaChunk(connection, OtaPackageType.FIRMWARE, fw.group(1), fw.group(2), payload);
        }
        Matcher sw = SW_REQUEST.matcher(topic);
        if (sw.matches()) {
            return publishOtaChunk(connection, OtaPackageType.SOFTWARE, sw.group(1), sw.group(2), payload);
        }
        return false;
    }

    private boolean publishOtaChunk(MqttConnection connection, OtaPackageType type,
                                    String requestId, String chunkStr, String metaJson) {
        JsonNode meta = JacksonUtils.toJsonNode(metaJson);
        if (meta == null || !meta.isObject() || !meta.hasNonNull("title")
                || !meta.hasNonNull("version") || !meta.hasNonNull("size")) {
            return false;
        }
        SessionInfo session = connection.sessionInfo();
        int chunk = Integer.parseInt(chunkStr);
        ResponseEntity<?> response = deviceApiService.downloadOta(
                session.tenantId(),
                session.deviceId(),
                session.deviceProfileId(),
                type,
                meta.get("title").asText(),
                meta.get("version").asText(),
                meta.path("size").asInt(),
                chunk);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return false;
        }
        byte[] bytes = response.getBody() instanceof byte[] body
                ? body
                : response.getBody().toString().getBytes(StandardCharsets.UTF_8);
        String responseTopic = type == OtaPackageType.FIRMWARE
                ? MqttTopics.Ota.firmwareResponseTopic(requestId, chunk)
                : MqttTopics.Ota.softwareResponseTopic(requestId, chunk);
        connection.listener().publishOtaChunk(responseTopic, bytes);
        return true;
    }

    private void handleProvision(MqttConnection connection, String payload) {
        ResponseEntity<String> response = deviceApiService.provision(payload);
        if (response.getBody() != null) {
            connection.listener().publishProvisionResponse(response.getBody());
        }
    }

    private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection == null || connection.sessionInfo() == null) {
            ctx.writeAndFlush(subAck(msg));
            return;
        }
        SessionInfo session = connection.sessionInfo();
        transportService.recordActivity(session);
        for (MqttTopicSubscription subscription : msg.payload().topicSubscriptions()) {
            String topic = subscription.topicName();
            if (subscription.qualityOfService() == MqttQoS.EXACTLY_ONCE) {
                connection.listener().setPublishQos(MqttQoS.EXACTLY_ONCE);
            }
            if (MqttTopics.Device.RPC_REQUEST_FILTER.equals(topic)
                    || topic.startsWith(MqttTopics.Device.RPC_REQUEST_PREFIX)) {
                transportRpcService.activateRpcSubscription(session);
            } else if (MqttTopics.Device.ATTRIBUTES.equals(topic)) {
                transportService.process(session, SubscribeToAttributeUpdatesMsg.subscribe(), TransportServiceCallback.EMPTY);
            }
        }
        ctx.writeAndFlush(subAck(msg));
    }

    private static void handleUnsubscribe(ChannelHandlerContext ctx, MqttMessage msg) {
        if (!(msg.variableHeader() instanceof MqttMessageIdVariableHeader variableHeader)) {
            return;
        }
        MqttFixedHeader header = new MqttFixedHeader(
                MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
        ctx.writeAndFlush(new MqttUnsubAckMessage(header, variableHeader));
    }

    private void handleSimple(ChannelHandlerContext ctx, MqttMessage msg) {
        if (msg.fixedHeader().messageType() == MqttMessageType.PINGREQ) {
            MqttFixedHeader header = new MqttFixedHeader(
                    MqttMessageType.PINGRESP, false, MqttQoS.AT_MOST_ONCE, false, 0);
            ctx.writeAndFlush(new MqttMessage(header));
        } else if (msg.fixedHeader().messageType() == MqttMessageType.DISCONNECT) {
            ctx.close();
        }
    }

    private void handlePubRel(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection == null || !(msg.variableHeader() instanceof MqttMessageIdVariableHeader variableHeader)) {
            return;
        }
        connection.qos2Inbound().remove(variableHeader.messageId());
        writePubReply(ctx, connection, MqttMessageType.PUBCOMP, variableHeader.messageId(), reasonSuccess());
    }

    private void handlePubAck(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection != null && msg.variableHeader() instanceof MqttMessageIdVariableHeader variableHeader) {
            connection.listener().onPubAck(variableHeader.messageId());
        }
    }

    private void handlePubRec(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection != null && msg.variableHeader() instanceof MqttMessageIdVariableHeader variableHeader) {
            connection.listener().onPubRec(variableHeader.messageId());
        }
    }

    private void handlePubComp(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttConnection connection = connection(ctx.channel());
        if (connection != null && msg.variableHeader() instanceof MqttMessageIdVariableHeader variableHeader) {
            connection.listener().onPubComp(variableHeader.messageId());
        }
    }

    private static TransportServiceCallback<Void> noopCallback(ChannelHandlerContext ctx, MqttPublishMessage msg) {
        return new TransportServiceCallback<>() {
            @Override
            public void onSuccess(Void unused) {
                ackPublish(ctx, msg);
            }

            @Override
            public void onError(Throwable e) {
                ackPublish(ctx, msg, reasonFor(e));
            }
        };
    }

    private static void ackPublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
        ackPublish(ctx, msg, reasonSuccess());
    }

    private static void ackPublish(ChannelHandlerContext ctx, MqttPublishMessage msg, byte reason) {
        MqttConnection connection = connection(ctx.channel());
        if (connection == null) {
            return;
        }
        if (msg.fixedHeader().qosLevel() == MqttQoS.AT_LEAST_ONCE) {
            writePubReply(ctx, connection, MqttMessageType.PUBACK, msg.variableHeader().packetId(), reason);
        } else if (msg.fixedHeader().qosLevel() == MqttQoS.EXACTLY_ONCE) {
            writePubReply(ctx, connection, MqttMessageType.PUBREC, msg.variableHeader().packetId(), reason);
        }
        if (!connection.mqtt5() && reason != reasonSuccess()) {
            ctx.close();
        }
    }

    private static void writePubReply(ChannelHandlerContext ctx, MqttConnection connection,
                                      MqttMessageType type, int packetId, byte reason) {
        MqttFixedHeader header = new MqttFixedHeader(
                type, false, type == MqttMessageType.PUBREL ? MqttQoS.AT_LEAST_ONCE : MqttQoS.AT_MOST_ONCE,
                false, 0);
        Object variableHeader = connection.mqtt5()
                ? new MqttPubReplyMessageVariableHeader(packetId, reason, MqttProperties.NO_PROPERTIES)
                : MqttMessageIdVariableHeader.from(packetId);
        ctx.writeAndFlush(new MqttMessage(header, variableHeader));
    }

    private static MqttConnAckMessage connAck(MqttConnectReturnCode code, boolean mqtt5,
                                              int receiveMaximum, int maximumPacketSize) {
        MqttFixedHeader header = new MqttFixedHeader(
                MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
        MqttProperties properties = new MqttProperties();
        if (mqtt5) {
            properties.add(new MqttProperties.IntegerProperty(MqttProperties.RECEIVE_MAXIMUM, receiveMaximum));
            properties.add(new MqttProperties.IntegerProperty(MqttProperties.MAXIMUM_QOS, 2));
            properties.add(new MqttProperties.IntegerProperty(MqttProperties.MAXIMUM_PACKET_SIZE, maximumPacketSize));
        }
        return new MqttConnAckMessage(header, new MqttConnAckVariableHeader(code, false, properties));
    }
    private static MqttSubAckMessage subAck(MqttSubscribeMessage msg) {
        List<Integer> granted = new ArrayList<>(msg.payload().topicSubscriptions().size());
        msg.payload().topicSubscriptions().forEach(subscription ->
                granted.add(subscription.qualityOfService().value()));
        MqttFixedHeader header = new MqttFixedHeader(
                MqttMessageType.SUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0);
        return new MqttSubAckMessage(header,
                MqttMessageIdVariableHeader.from(msg.variableHeader().messageId()),
                new MqttSubAckPayload(granted));
    }

    private DeviceCredentialService.DevicePrincipal authenticate(ChannelHandlerContext ctx,
                                                                 String clientId,
                                                                 String username,
                                                                 String password) {
        X509Certificate certificate = MqttSslPeerCertificateHandler.peerCertificate(ctx);
        if (certificate != null) {
            DeviceCredentialService.DevicePrincipal x509Principal = authenticateX509(certificate);
            if (x509Principal != null) {
                return x509Principal;
            }
            return null;
        }
        if (StringUtils.hasText(username)) {
            DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateAccessToken(username);
            if (principal != null) {
                return principal;
            }
        }
        if (StringUtils.hasText(password)) {
            DeviceCredentialService.DevicePrincipal principal = credentialsService.authenticateAccessToken(password);
            if (principal != null) {
                return principal;
            }
        }
        return credentialsService.authenticateMqttCredentials(clientId, username, password);
    }

    private DeviceCredentialService.DevicePrincipal authenticateX509(X509Certificate certificate) {
        if (!skipValidityCheckForClientCert) {
            try {
                certificate.checkValidity();
            } catch (Exception exception) {
                log.debug("X509 certificate validity check failed: {}", exception.getMessage());
                return null;
            }
        }
        return credentialsService.authenticateX509Certificate(
                X509CertificateUtil.sha3HashHex(certificate));
    }

    private static boolean isProvisionConnection(String clientId, String username) {
        return MqttTopics.Provision.CLIENT_ID.equals(clientId) || MqttTopics.Provision.CLIENT_ID.equals(username);
    }

    private static String password(MqttConnectMessage msg) {
        byte[] bytes = msg.payload().passwordInBytes();
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] payloadBytes(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }


    private static String propertyString(MqttProperties properties, int propertyId) {
        if (properties == null) {
            return null;
        }
        Object value = properties.getProperty(propertyId);
        return value instanceof MqttProperties.StringProperty property ? property.value() : null;
    }
    private static String contentType(MqttProperties properties) {
        String contentType = propertyString(properties, MqttProperties.CONTENT_TYPE);
        return contentType == null ? "application/json" : contentType;
    }

    private static int suffixInt(String topic, String prefix) {
        return Integer.parseInt(topic.substring(prefix.length()));
    }

    private static GetAttributeRequestMsg parseAttributeRequest(int requestId, String json) {
        JsonNode body = JacksonUtils.toJsonNode(json);
        return new GetAttributeRequestMsg(requestId, parseKeys(body, "clientKeys"), parseKeys(body, "sharedKeys"));
    }

    private static List<String> parseKeys(JsonNode body, String field) {
        if (body == null || !body.has(field)) {
            return List.of();
        }
        JsonNode node = body.get(field);
        if (node.isArray()) {
            List<String> keys = new ArrayList<>();
            node.forEach(item -> keys.add(item.asText()));
            return keys;
        }
        String raw = node.asText("");
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return List.of(raw.split(","));
    }
    private static int propertyInt(MqttProperties properties, int propertyId, int defaultValue) {
        if (properties == null || !(properties.getProperty(propertyId) instanceof MqttProperties.IntegerProperty property)) {
            return defaultValue;
        }
        return property.value();
    }

    private static byte reasonSuccess() {
        return MqttReasonCodes.PubAck.SUCCESS.byteValue();
    }

    private static byte reasonFor(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ResponseStatusException response) {
                int status = response.getStatusCode().value();
                if (status == 429) {
                    return MqttReasonCodes.PubAck.QUOTA_EXCEEDED.byteValue();
                }
                if (status == 400) {
                    return MqttReasonCodes.PubAck.PAYLOAD_FORMAT_INVALID.byteValue();
                }
                if (status == 403) {
                    return MqttReasonCodes.PubAck.NOT_AUTHORIZED.byteValue();
                }
            }
            cause = cause.getCause();
        }
        return MqttReasonCodes.PubAck.UNSPECIFIED_ERROR.byteValue();
    }


    private static MqttConnection connection(Channel channel) {
        return channel.attr(MqttConnection.KEY).get();
    }

    private static void setConnection(Channel channel, MqttConnection connection) {
        channel.attr(MqttConnection.KEY).set(connection);
    }
    private record MqttConnection(SessionInfo sessionInfo,
                                  MqttSessionListener listener,
                                  boolean provisionOnly,
                                  boolean mqtt5,
                                  Set<Integer> qos2Inbound) {
        private static final io.netty.util.AttributeKey<MqttConnection> KEY =
                io.netty.util.AttributeKey.valueOf("mqttConnection");
    }
}
