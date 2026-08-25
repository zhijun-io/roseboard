package com.roseboard.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialAuthCacheKeysTest {

    @Test
    void accessTokenUsesSingleCredentialsIdKey() {
        assertThat(CredentialAuthCacheKeys.from("ACCESS_TOKEN", "token-value", null))
                .containsExactly(CacheKeyBuilder.credentialsAuth("ACCESS_TOKEN", "token-value"));
    }

    @Test
    void mqttIncludesUsernameAliasWhenDistinctFromCredentialsId() {
        String credentialsValue = """
                {"clientId":"device-1","userName":"mqtt-user","password":"secret"}
                """;
        String credentialsId = MqttCredentialLookups.credentialId("device-1", "mqtt-user");

        assertThat(CredentialAuthCacheKeys.from("MQTT_BASIC", credentialsId, credentialsValue))
                .containsExactly(
                        CacheKeyBuilder.credentialsAuth("MQTT_BASIC", credentialsId),
                        CacheKeyBuilder.credentialsAuth("MQTT_BASIC", "mqtt-user"));
    }

    @Test
    void mqttUsernameOnlyCredentialsDoNotDuplicateAlias() {
        String credentialsValue = """
                {"userName":"mqtt-user","password":"secret"}
                """;
        assertThat(CredentialAuthCacheKeys.from("MQTT_BASIC", "mqtt-user", credentialsValue))
                .containsExactly(CacheKeyBuilder.credentialsAuth("MQTT_BASIC", "mqtt-user"));
    }

    @Test
    void mqttInvalidPayloadStillEvictsCredentialsId() {
        assertThat(CredentialAuthCacheKeys.from("MQTT_BASIC", "mqtt-user", "not-json"))
                .containsExactly(CacheKeyBuilder.credentialsAuth("MQTT_BASIC", "mqtt-user"));
    }
}
