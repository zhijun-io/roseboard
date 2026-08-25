package com.roseboard.infrastructure.transport.mqtt;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "roseboard.transport.mqtt", name = "enabled", havingValue = "true")
public class MqttTransportServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MqttTransportServer.class);

    private final MqttTransportProperties properties;
    private final MqttTransportHandler handler;
    private final MqttSslContextFactory sslContextFactory;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final List<Channel> channels = new ArrayList<>();
    private volatile boolean running;
    private int tcpPort = -1;
    private int sslPort = -1;
    private int webSocketPort = -1;
    private int webSocketSslPort = -1;

    public MqttTransportServer(MqttTransportProperties properties,
                               MqttTransportHandler handler,
                               MqttSslContextFactory sslContextFactory) {
        this.properties = properties;
        this.handler = handler;
        this.sslContextFactory = sslContextFactory;
    }

    public int boundPort() {
        return tcpPort > 0 ? tcpPort : properties.getPort();
    }

    public int boundSslPort() {
        return sslPort;
    }

    public int boundWebSocketPort() {
        return webSocketPort;
    }

    public int boundWebSocketSslPort() {
        return webSocketSslPort;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            tcpPort = bind(MqttListenerMode.TCP, properties.getBindAddress(), properties.getPort());
            log.info("MQTT TCP listening on {}:{}", properties.getBindAddress(), tcpPort);

            if (properties.getSsl().isEnabled()) {
                if (!sslContextFactory.isConfigured()) {
                    throw new IllegalStateException(
                            "roseboard.transport.mqtt.ssl.enabled requires key-store configuration");
                }
                MqttTransportProperties.Ssl ssl = properties.getSsl();
                sslPort = bind(MqttListenerMode.TLS, ssl.getBindAddress(), ssl.getPort());
                log.info("MQTT TLS listening on {}:{}", ssl.getBindAddress(), sslPort);
            }

            if (properties.getWebSocket().isEnabled()) {
                MqttTransportProperties.WebSocket webSocket = properties.getWebSocket();
                webSocketPort = bind(MqttListenerMode.WEBSOCKET, webSocket.getBindAddress(), webSocket.getPort());
                log.info("MQTT WebSocket listening on {}:{}{}",
                        webSocket.getBindAddress(), webSocketPort, webSocket.getPath());

                if (webSocket.isSslEnabled()) {
                    if (!sslContextFactory.isConfigured()) {
                        throw new IllegalStateException(
                                "roseboard.transport.mqtt.web-socket.ssl-enabled requires SSL keystore");
                    }
                    webSocketSslPort = bind(MqttListenerMode.WEBSOCKET_TLS,
                            webSocket.getBindAddress(), webSocket.getSslPort());
                    log.info("MQTT WebSocket TLS listening on {}:{}{}",
                            webSocket.getBindAddress(), webSocketSslPort, webSocket.getPath());
                }
            }
            running = true;
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    private int bind(MqttListenerMode mode, String bindAddress, int port) {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new MqttPipelineInitializer(
                        mode, handler, sslContextFactory, properties.getWebSocket().getPath()));
        Channel channel = bootstrap.bind(bindAddress, port).syncUninterruptibly().channel();
        channels.add(channel);
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @PreDestroy
    @Override
    public void stop() {
        running = false;
        for (Channel channel : channels) {
            channel.close().syncUninterruptibly();
        }
        channels.clear();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        tcpPort = -1;
        sslPort = -1;
        webSocketPort = -1;
        webSocketSslPort = -1;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
