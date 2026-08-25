package com.roseboard.infrastructure.transport.mqtt;

import com.roseboard.infrastructure.transport.AttributeUpdateNotificationMsg;
import com.roseboard.infrastructure.transport.GetAttributeResponseMsg;
import com.roseboard.infrastructure.transport.JsonConverter;
import com.roseboard.infrastructure.transport.SessionMsgListener;
import com.roseboard.infrastructure.transport.ToServerRpcResponseMsg;
import com.roseboard.infrastructure.transport.TransportToDevicePayload;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class MqttSessionListener implements SessionMsgListener {
    private final Channel channel;
    private final int receiveMaximum;
    private final int maximumPacketSize;
    private final AtomicInteger nextPacketId = new AtomicInteger();
    private final Map<Integer, MqttQoS> pendingPublishes = new ConcurrentHashMap<>();
    private volatile MqttQoS publishQos = MqttQoS.AT_LEAST_ONCE;

    MqttSessionListener(Channel channel, int receiveMaximum, int maximumPacketSize) {
        this.channel = channel;
        this.receiveMaximum = Math.max(1, Math.min(65_535, receiveMaximum));
        this.maximumPacketSize = Math.max(1, maximumPacketSize);
    }
    void onPubAck(int packetId) {
        pendingPublishes.remove(packetId);
    }

    void onPubRec(int packetId) {
        if (pendingPublishes.get(packetId) != MqttQoS.EXACTLY_ONCE || !channel.isActive()) {
            return;
        }
        MqttFixedHeader header = new MqttFixedHeader(
                MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);
        channel.writeAndFlush(new MqttMessage(header, MqttMessageIdVariableHeader.from(packetId)));
    }

    void onPubComp(int packetId) {
        pendingPublishes.remove(packetId);
    }
    void setPublishQos(MqttQoS qos) {
        publishQos = qos == MqttQoS.AT_MOST_ONCE ? MqttQoS.AT_MOST_ONCE : qos;
    }

    private void publish(String topic, byte[] payload) {
        publish(topic, payload, publishQos);
    }

    @Override
    public void onMessage(TransportToDevicePayload message) {
        if (message.requestId() == null) {
            return;
        }
        publish(MqttTopics.Device.RPC_REQUEST_PREFIX + message.requestId(),
                JsonConverter.toDeviceRpcJson(message).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onGetAttributesResponse(GetAttributeResponseMsg msg) {
        publish(MqttTopics.Device.ATTRIBUTES_RESPONSE_PREFIX + msg.requestId(),
                JsonConverter.toJson(msg).getBytes(StandardCharsets.UTF_8));
    }

    public void onAttributeUpdate(AttributeUpdateNotificationMsg msg) {
        publish(MqttTopics.Device.ATTRIBUTES,
                JsonConverter.toJson(msg).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onToServerRpcResponse(ToServerRpcResponseMsg msg) {
        publish(MqttTopics.Device.RPC_RESPONSE_PREFIX + msg.requestId(),
                JsonConverter.toJson(msg).getBytes(StandardCharsets.UTF_8));
    }

    void publishProvisionResponse(String json) {
        publish(MqttTopics.Provision.RESPONSE, json.getBytes(StandardCharsets.UTF_8));
    }

    void publishOtaChunk(String topic, byte[] payload) {
        publish(topic, payload);
    }

    private void publish(String topic, byte[] payload, MqttQoS qos) {
        if (!channel.isActive() || payload.length > maximumPacketSize
                || (qos != MqttQoS.AT_MOST_ONCE && pendingPublishes.size() >= receiveMaximum)) {
            return;
        }
        int packetId = qos == MqttQoS.AT_MOST_ONCE ? -1 : nextPacketId();
        MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, false, qos, false, 0);
        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, packetId);
        if (qos != MqttQoS.AT_MOST_ONCE) {
            pendingPublishes.put(packetId, qos);
        }
        channel.writeAndFlush(new MqttPublishMessage(header, variableHeader, Unpooled.wrappedBuffer(payload)));
    }

    private int nextPacketId() {
        for (;;) {
            int candidate = nextPacketId.updateAndGet(current -> current == 65_535 ? 1 : current + 1);
            if (!pendingPublishes.containsKey(candidate)) {
                return candidate;
            }
        }
    }
}
