package com.roseboard.infrastructure.transport.mqtt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MqttTransportProperties.class)
@ConditionalOnProperty(prefix = "roseboard.transport.mqtt", name = "enabled", havingValue = "true")
public class MqttTransportConfiguration {
}
