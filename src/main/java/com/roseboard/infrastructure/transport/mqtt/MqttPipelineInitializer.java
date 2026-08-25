package com.roseboard.infrastructure.transport.mqtt;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.ssl.SslHandler;

enum MqttListenerMode {
    TCP,
    TLS,
    WEBSOCKET,
    WEBSOCKET_TLS
}

final class MqttPipelineInitializer extends ChannelInitializer<SocketChannel> {
    private final MqttListenerMode mode;
    private final MqttTransportHandler handler;
    private final MqttSslContextFactory sslContextFactory;
    private final String webSocketPath;

    MqttPipelineInitializer(MqttListenerMode mode,
                            MqttTransportHandler handler,
                            MqttSslContextFactory sslContextFactory,
                            String webSocketPath) {
        this.mode = mode;
        this.handler = handler;
        this.sslContextFactory = sslContextFactory;
        this.webSocketPath = webSocketPath;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (mode == MqttListenerMode.TLS || mode == MqttListenerMode.WEBSOCKET_TLS) {
            SslHandler sslHandler = sslContextFactory.newHandler();
            pipeline.addLast("ssl", sslHandler);
            pipeline.addLast("ssl-peer-cert", new MqttSslPeerCertificateHandler());
        }
        if (mode == MqttListenerMode.WEBSOCKET || mode == MqttListenerMode.WEBSOCKET_TLS) {
            pipeline.addLast("http-codec", new HttpServerCodec());
            pipeline.addLast("http-aggregator", new HttpObjectAggregator(65_536));
            pipeline.addLast("websocket-protocol",
                    new WebSocketServerProtocolHandler(webSocketPath, "mqtt", true, 65_536));
            pipeline.addLast("websocket-codec", new MqttWebSocketCodec());
        }
        pipeline.addLast("mqtt-decoder", new MqttDecoder());
        pipeline.addLast("mqtt-encoder", MqttEncoder.INSTANCE);
        pipeline.addLast("mqtt-handler", handler);
    }
}
