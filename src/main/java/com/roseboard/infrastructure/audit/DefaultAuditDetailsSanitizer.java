package com.roseboard.infrastructure.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 默认脱敏策略：按字段名剔除常见凭证字段，递归处理嵌套对象与数组。
 */
final class DefaultAuditDetailsSanitizer {

    private DefaultAuditDetailsSanitizer() {
    }

    static JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull()) {
            return value;
        }
        if (value.isObject()) {
            ObjectNode result = (ObjectNode) value.deepCopy();
            ArrayList<String> names = new ArrayList<>(result.propertyNames());
            for (String name : names) {
                if (isSensitive(name)) {
                    result.remove(name);
                } else {
                    result.set(name, sanitize(result.get(name)));
                }
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = (ArrayNode) value.deepCopy();
            for (int index = 0; index < result.size(); index++) {
                result.set(index, sanitize(result.get(index)));
            }
            return result;
        }
        return value;
    }

    private static boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("clientsecret")
                || normalized.contains("secret")
                || normalized.contains("privatekey")
                || normalized.contains("passphrase")
                || normalized.contains("verificationcode")
                || normalized.contains("refreshtoken")
                || normalized.equals("otp")
                || normalized.equals("pin")
                || normalized.equals("authorization")
                || normalized.equals("credential")
                || normalized.equals("credentials")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("secret");
    }
}
