package com.roseboard.infrastructure.notification.model;

import java.util.Map;

public record ResolvedChannelConfig(
        ChannelKind kind,
        ConfigScope scope,
        ConnectionMode connectionMode,
        boolean enabled,
        Map<String, Object> config,
        Map<String, Object> secrets
) {
    public ResolvedChannelConfig {
        config = config == null ? Map.of() : Map.copyOf(config);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    public boolean isComplete() {
        return switch (kind) {
            case WEB -> true;
            case EMAIL -> configString("smtpHost") != null && intValue(config, "smtpPort") > 0;
            case SMS -> configString("baseUrl") != null && secretString("apiKey") != null;
        };
    }

    public String configString(String key) {
        return string(config, key);
    }

    public String secretString(String key) {
        return string(secrets, key);
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static int intValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }
}
