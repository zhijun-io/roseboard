package com.roseboard.infrastructure.notification.channel;

import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

public final class SmtpMailConfiguration {
    private SmtpMailConfiguration() {
    }

    public static JsonNode toSmtpMailClientJson(ResolvedChannelConfig channelConfig, ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        copyField(node, "smtpHost", channelConfig.config().get("smtpHost"));
        copyField(node, "smtpPort", channelConfig.config().get("smtpPort"));
        copyField(node, "username", channelConfig.config().get("username"));
        copyField(node, "password", channelConfig.secrets().get("password"));
        copyField(node, "mailFrom", channelConfig.config().get("from"));
        node.put("enableTls", bool(channelConfig.config().get("tls"), true));
        Object oauth2 = channelConfig.config().get("oauth2");
        if (oauth2 instanceof Map<?, ?> oauthMap && !oauthMap.isEmpty()) {
            node.put("enableOauth2", true);
            JsonNode oauthNode = objectMapper.valueToTree(oauthMap);
            if (oauthNode instanceof ObjectNode oauthObject) {
            for (var entry : oauthObject.properties()) {
                node.set(entry.getKey(), entry.getValue());
            }
            }
        }
        copyField(node, "refreshToken", firstNonNull(
                channelConfig.secrets().get("refreshToken"),
                channelConfig.config().get("refreshToken")));
        copyField(node, "clientSecret", channelConfig.secrets().get("clientSecret"));
        return node;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static void copyField(ObjectNode node, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            node.put(field, number.intValue());
            return;
        }
        String text = value.toString();
        if (!text.isBlank()) {
            node.put(field, text);
        }
    }

    private static boolean bool(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }
}
