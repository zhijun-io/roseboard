package com.roseboard.infrastructure.websocket;

import com.roseboard.infrastructure.websocket.cluster.WebSocketClusterProperties;
import com.roseboard.infrastructure.websocket.cluster.WebSocketPushGateway;
import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WebSocketClusterProperties.class)
public class WebSocketConfiguration implements WebSocketConfigurer {

    public static final String WS_API_ENDPOINT = "/api/ws";
    public static final String WS_PLUGINS_ENDPOINT = "/api/ws/plugins/";
    private static final String WS_API_MAPPING = "/api/ws/**";

    private final WebSocketHandler webSocketHandler;

    @Value("${server.ws.max_text_message_buffer_size:32768}")
    private int maxTextMessageBufferSize;
    @Value("${server.ws.max_binary_message_buffer_size:32768}")
    private int maxBinaryMessageBufferSize;

    public WebSocketConfiguration(WebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer(ServletContext servletContext) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean() {
            @Override
            public void afterPropertiesSet() {
                if (servletContext.getAttribute("jakarta.websocket.server.ServerContainer") == null) {
                    return;
                }
                super.afterPropertiesSet();
            }
        };
        container.setMaxTextMessageBufferSize(maxTextMessageBufferSize);
        container.setMaxBinaryMessageBufferSize(maxBinaryMessageBufferSize);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, WS_API_MAPPING)
                .setAllowedOriginPatterns("*");
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "server.ws.cluster", name = "enabled", havingValue = "true")
    RedisMessageListenerContainer webSocketPushListenerContainer(RedisConnectionFactory connectionFactory,
                                                                 WebSocketPushGateway gateway,
                                                                 WebSocketClusterProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> gateway.onRedisMessage(message.toString()),
                ChannelTopic.of(properties.getPushChannel()));
        return container;
    }
}
