package com.roseboard.device.connectivity;

import com.roseboard.common.JacksonUtils;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceConnectivityCommandServiceTest {
    @Mock
    private DeviceCredentialService credentialsService;
    @Mock
    private DeviceProfileService profileService;

    @Test
    void generatesAccessTokenHttpAndMqttCommands() {
        DeviceEntity device = device();
        DeviceCredentialEntity credentials = credentials("ACCESS_TOKEN", "token-1", null);
        when(credentialsService.findByDeviceId(device.getId())).thenReturn(credentials);
        when(profileService.findById(device.getDeviceProfileId())).thenReturn(profile());

        JsonNode commands = service().find("https://example.test:8443", device);

        assertTrue(commands.path("http").asText().contains("/api/http/'token-1'/telemetry"));
        assertTrue(commands.path("mqtt").path("command").asText().contains("-u 'token-1'"));
        assertTrue(commands.path("mqtt").path("command").asText().contains("-t 'custom/telemetry'"));
    }

    @Test
    void generatesMqttBasicCommand() {
        DeviceEntity device = device();
        DeviceCredentialEntity credentials = credentials("MQTT_BASIC", "id", "{\"userName\":\"user\",\"password\":\"secret\"}");
        when(credentialsService.findByDeviceId(device.getId())).thenReturn(credentials);
        when(profileService.findById(any())).thenReturn(profile());

        JsonNode commands = service().find("http://localhost", device);
        String command = commands.path("mqtt").path("command").asText();
        assertTrue(command.contains("-u 'user'"));
        assertTrue(command.contains("-P 'secret'"));
    }

    private DeviceConnectivityCommandService service() {
        return new DeviceConnectivityCommandService(credentialsService, profileService, 1883, true);
    }

    private static DeviceEntity device() {
        DeviceEntity device = new DeviceEntity();
        device.setId(UUID.randomUUID());
        device.setDeviceProfileId(UUID.randomUUID());
        device.setTenantId(UUID.randomUUID());
        return device;
    }

    private static DeviceProfileEntity profile() {
        DeviceProfileEntity profile = new DeviceProfileEntity();
        profile.setProfileData(JacksonUtils.objectMapper().readTree("{\"transportConfiguration\":{\"deviceTelemetryTopic\":\"custom/telemetry\"}}"));
        return profile;
    }

    private static DeviceCredentialEntity credentials(String type, String id, String value) {
        DeviceCredentialEntity credentials = new DeviceCredentialEntity();
        credentials.setCredentialsType(type);
        credentials.setCredentialsId(id);
        credentials.setCredentialsValue(value);
        credentials.setEnabled(true);
        return credentials;
    }
}
