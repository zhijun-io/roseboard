package com.roseboard.device.connectivity;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DeviceConnectivityCommandService {
    private static final String DEFAULT_TOPIC = "devices/me/telemetry";

    private final DeviceCredentialService credentialsService;
    private final DeviceProfileService profileService;
    private final int mqttPort;
    private final boolean mqttEnabled;

    public DeviceConnectivityCommandService(DeviceCredentialService credentialsService,
                                            DeviceProfileService profileService,
                                            @Value("${roseboard.transport.mqtt.port:1883}") int mqttPort,
                                            @Value("${roseboard.transport.mqtt.enabled:false}") boolean mqttEnabled) {
        this.credentialsService = credentialsService;
        this.profileService = profileService;
        this.mqttPort = mqttPort;
        this.mqttEnabled = mqttEnabled;
    }

    public JsonNode find(String baseUrl, DeviceEntity device) {
        DeviceCredentialEntity credentials = credentialsService.findByDeviceId(device.getId());
        if (credentials == null || !Boolean.TRUE.equals(credentials.getEnabled())) {
            return JacksonUtils.objectMapper().createObjectNode();
        }

        ObjectNode commands = JacksonUtils.newObjectNode();
        if (DeviceCredentialType.ACCESS_TOKEN.name().equals(credentials.getCredentialsType())
                && credentials.getCredentialsId() != null) {
            commands.put("http", "curl -v -X POST " + baseUrl + "/api/http/"
                    + shell(credentials.getCredentialsId()) + "/telemetry"
                    + " --header 'Content-Type:application/json' --data '{\"temperature\":25}'");
        }

        if (mqttEnabled) {
            addMqttCommand(commands, baseUrl, device, credentials);
        }
        return commands;
    }

    private void addMqttCommand(ObjectNode commands, String baseUrl, DeviceEntity device,
                                DeviceCredentialEntity credentials) {
        String topic = telemetryTopic(device);
        String host = host(baseUrl);
        ObjectNode mqtt = JacksonUtils.newObjectNode();
        mqtt.put("command", mqttCommand(host, topic, credentials));
        mqtt.put("host", host);
        mqtt.put("port", mqttPort);
        mqtt.put("topic", topic);
        commands.set("mqtt", mqtt);
    }

    private String mqttCommand(String host, String topic, DeviceCredentialEntity credentials) {
        StringBuilder command = new StringBuilder("mosquitto_pub -d -q 1 -h ")
                .append(shell(host)).append(" -p ").append(mqttPort)
                .append(" -t ").append(shell(topic));
        if (DeviceCredentialType.ACCESS_TOKEN.name().equals(credentials.getCredentialsType())) {
            command.append(" -u ").append(shell(credentials.getCredentialsId()));
        } else if (DeviceCredentialType.MQTT_BASIC.name().equals(credentials.getCredentialsType())) {
            JsonNode value = JacksonUtils.fromString(credentials.getCredentialsValue(), JsonNode.class);
            if (value != null && value.hasNonNull("userName")) {
                command.append(" -u ").append(shell(value.get("userName").asText()));
            }
            if (value != null && value.hasNonNull("password")) {
                command.append(" -P ").append(shell(value.get("password").asText()));
            }
        } else if (DeviceCredentialType.X509_CERTIFICATE.name().equals(credentials.getCredentialsType())) {
            return "check documentation";
        }
        return command.append(" -m '{\"temperature\":25}'").toString();
    }

    private String telemetryTopic(DeviceEntity device) {
        DeviceProfileEntity profile = profileService.findById(device.getDeviceProfileId());
        JsonNode data = profile == null ? null : profile.getProfileData();
        JsonNode configuration = data == null ? null : data.get("transportConfiguration");
        String topic = configuration == null ? null : configuration.path("deviceTelemetryTopic").asText(null);
        return topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic;
    }

    private static String host(String baseUrl) {
        String value = baseUrl;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String shell(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }
}
