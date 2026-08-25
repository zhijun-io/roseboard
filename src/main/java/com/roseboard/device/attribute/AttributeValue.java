package com.roseboard.device.attribute;

import com.roseboard.common.JacksonUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public record AttributeValue(Object value) {
    private static final int MAX_JSON_DEPTH = 8;
    private static final int MAX_JSON_CHARS = 64 * 1024;

    public AttributeValue {
        validate(value, 0);
        if (value != null) {
            try {
                int bytes = JacksonUtils.objectMapper().writeValueAsString(value)
                        .getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_JSON_CHARS) {
                    throw new IllegalArgumentException("Attribute value exceeds size limit");
                }
            } catch (tools.jackson.core.JacksonException exception) {
                throw new IllegalArgumentException("Attribute value cannot be serialized", exception);
            }
        }
    }

    private static void validate(Object value, int depth) {
        if (value == null || value instanceof Boolean || value instanceof String
                || value instanceof Long || value instanceof Integer
                || value instanceof Double || value instanceof Float) {
            if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("Attribute value must be finite");
            }
            return;
        }
        if (depth >= MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("Attribute value nesting is too deep");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (!(key instanceof String string) || string.isBlank()) {
                    throw new IllegalArgumentException("Attribute object keys must be non-blank strings");
                }
                validate(item, depth + 1);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> validate(item, depth + 1));
            return;
        }
        throw new IllegalArgumentException("Unsupported attribute value type: " + value.getClass());
    }
}
