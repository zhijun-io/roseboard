package com.roseboard.cache;

import com.roseboard.common.JacksonUtils;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves all credential-auth cache keys that must be invalidated for one credentials row. */
public final class CredentialAuthCacheKeys {
    static final String MQTT_BASIC = "MQTT_BASIC";

    private CredentialAuthCacheKeys() {
    }

    public static List<String> from(String type, String credentialsId, String credentialsValue) {
        Set<String> keys = new LinkedHashSet<>();
        add(keys, type, credentialsId);
        if (MQTT_BASIC.equals(type)) {
            addMqttAliases(keys, credentialsId, credentialsValue);
        }
        return List.copyOf(keys);
    }

    private static void addMqttAliases(Set<String> keys, String credentialsId, String credentialsValue) {
        if (credentialsValue == null || credentialsValue.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = JacksonUtils.toJsonNode(credentialsValue);
        } catch (RuntimeException ignored) {
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        String clientId = text(node, "clientId");
        String userName = firstNonBlank(text(node, "userName"), text(node, "username"));
        add(keys, MQTT_BASIC, MqttCredentialLookups.credentialId(clientId, userName));
        if (!isBlank(userName) && !userName.equals(credentialsId)) {
            add(keys, MQTT_BASIC, userName);
        }
    }

    private static void add(Set<String> keys, String type, String lookup) {
        if (type == null || type.isBlank() || lookup == null || lookup.isBlank()) {
            return;
        }
        keys.add(CacheKeyBuilder.credentialsAuth(type, lookup));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
