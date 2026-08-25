package com.roseboard.infrastructure.transport;

import com.roseboard.common.JacksonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonConverter {
    private static final String CAN_T_PARSE_VALUE = "Can't parse value: ";

    private JsonConverter() {
    }

    public static PostTelemetryMsg convertToTelemetryProto(String json) {
        return convertToTelemetryProto(JacksonUtils.toJsonNode(json), System.currentTimeMillis());
    }

    public static PostTelemetryMsg convertToTelemetryProto(JsonNode jsonElement) {
        return convertToTelemetryProto(jsonElement, System.currentTimeMillis());
    }

    public static PostAttributeMsg convertToAttributesProto(String json) {
        return convertToAttributesProto(JacksonUtils.toJsonNode(json));
    }

    public static PostAttributeMsg convertToAttributesProto(JsonNode jsonElement) {
        if (jsonElement == null || !jsonElement.isObject()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + jsonElement);
        }
        return new PostAttributeMsg(parseValues(jsonElement));
    }

    public static String toDeviceRpcJson(TransportToDevicePayload payload) {
        ObjectNode result = JacksonUtils.newObjectNode();
        if (payload.requestId() != null) {
            result.put("id", payload.requestId());
        }
        result.put("method", payload.method());
        if (payload.params() != null) {
            result.set("params", payload.params());
        } else {
            result.set("params", JacksonUtils.newObjectNode());
        }
        return result.toString();
    }

    public static String toJson(AttributeUpdateNotificationMsg payload) {
        ObjectNode result = JacksonUtils.newObjectNode();
        if (payload.sharedUpdated() != null) {
            for (KeyValueEntry entry : payload.sharedUpdated()) {
                addKeyValue(result, entry);
            }
        }
        if (payload.sharedDeleted() != null && !payload.sharedDeleted().isEmpty()) {
            ArrayNode deleted = JacksonUtils.newArrayNode();
            payload.sharedDeleted().forEach(deleted::add);
            result.set("deleted", deleted);
        }
        return result.toString();
    }

    public static String toJson(ToServerRpcResponseMsg payload) {
        if (payload.error() != null) {
            ObjectNode result = JacksonUtils.newObjectNode();
            result.put("error", payload.error());
            return result.toString();
        }
        return payload.payload() == null ? "{}" : payload.payload().toString();
    }

    private static void addKeyValue(ObjectNode target, KeyValueEntry entry) {
        Object value = entry.toObject();
        if (value instanceof Boolean bool) {
            target.put(entry.key(), bool);
        } else if (value instanceof Number number) {
            target.put(entry.key(), number.doubleValue());
        } else if (value instanceof String string) {
            target.put(entry.key(), string);
        } else if (value != null) {
            target.putPOJO(entry.key(), value);
        } else {
            target.putNull(entry.key());
        }
    }

    public static String toJson(GetAttributeResponseMsg payload) {
        ObjectNode result = JacksonUtils.newObjectNode();
        if (payload.clientAttributeList() != null && !payload.clientAttributeList().isEmpty()) {
            result.set("client", toObject(payload.clientAttributeList()));
        }
        if (payload.sharedAttributeList() != null && !payload.sharedAttributeList().isEmpty()) {
            result.set("shared", toObject(payload.sharedAttributeList()));
        }
        return result.toString();
    }

    public record ClaimRequest(String secretKey, long durationMs) {
    }

    public static ClaimRequest parseClaimRequest(String json) {
        String secretKey = "";
        long durationMs = 0L;
        if (json == null || json.isBlank()) {
            return new ClaimRequest(secretKey, durationMs);
        }
        JsonNode body = JacksonUtils.toJsonNode(json);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + body);
        }
        if (body.has("secretKey")) {
            secretKey = body.get("secretKey").asText("");
        }
        if (body.has("durationMs")) {
            durationMs = body.get("durationMs").asLong(0L);
        }
        return new ClaimRequest(secretKey, durationMs);
    }

    public static JsonNode parseProvisionRequest(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + json);
        }
        JsonNode body = JacksonUtils.toJsonNode(json);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + body);
        }
        return body;
    }

    public static String toProvisionJson(String status, String credentialsType, String credentialsValue, String errorMsg) {
        ObjectNode body = JacksonUtils.newObjectNode();
        body.put("status", status);
        if (errorMsg != null) {
            body.put("errorMsg", errorMsg);
        }
        if (credentialsType != null) {
            body.put("credentialsType", credentialsType);
        }
        if (credentialsValue != null) {
            body.put("credentialsValue", credentialsValue);
        }
        return body.toString();
    }

    private static ObjectNode toObject(List<KeyValueEntry> entries) {
        ObjectNode object = JacksonUtils.newObjectNode();
        for (KeyValueEntry entry : entries) {
            switch (entry.type()) {
                case BOOLEAN_V -> object.put(entry.key(), entry.boolV());
                case STRING_V -> object.put(entry.key(), entry.stringV());
                case LONG_V -> object.put(entry.key(), entry.longV());
                case DOUBLE_V -> object.put(entry.key(), entry.doubleV());
                case JSON_V -> {
                    try {
                        object.set(entry.key(), JacksonUtils.toJsonNode(entry.jsonV()));
                    } catch (JacksonException e) {
                        object.put(entry.key(), entry.jsonV());
                    }
                }
            }
        }
        return object;
    }

    private static PostTelemetryMsg convertToTelemetryProto(JsonNode jsonElement, long systemTs) {
        List<PostTelemetryMsg.TsKvList> tsKvList = new ArrayList<>();
        convertToTelemetry(jsonElement, systemTs, tsKvList);
        return new PostTelemetryMsg(tsKvList);
    }

    private static void convertToTelemetry(JsonNode jsonElement, long systemTs, List<PostTelemetryMsg.TsKvList> result) {
        if (jsonElement.isObject()) {
            parseObject(systemTs, result, jsonElement);
        } else if (jsonElement.isArray()) {
            for (JsonNode element : jsonElement) {
                if (element.isObject()) {
                    parseObject(systemTs, result, element);
                } else {
                    throw new IllegalArgumentException(CAN_T_PARSE_VALUE + element);
                }
            }
        } else {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + jsonElement);
        }
    }

    private static void parseObject(long systemTs, List<PostTelemetryMsg.TsKvList> result, JsonNode object) {
        if (object.has("ts") && object.has("values")) {
            result.add(new PostTelemetryMsg.TsKvList(object.get("ts").asLong(), parseValues(object.get("values"))));
        } else {
            result.add(new PostTelemetryMsg.TsKvList(systemTs, parseValues(object)));
        }
    }

    private static List<KeyValueEntry> parseValues(JsonNode valuesObject) {
        if (valuesObject == null || !valuesObject.isObject()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + valuesObject);
        }
        List<KeyValueEntry> result = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = valuesObject.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.add(parseValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static KeyValueEntry parseValue(String key, JsonNode element) {
        if (element == null || element.isNull()) {
            throw new IllegalArgumentException(CAN_T_PARSE_VALUE + element);
        }
        if (element.isBoolean()) {
            return new KeyValueEntry(key, KeyValueType.BOOLEAN_V, element.asBoolean(), null, 0L, 0D, null);
        }
        if (element.isNumber()) {
            return buildNumeric(key, element.asText());
        }
        if (element.isTextual()) {
            String text = element.asText();
            if (isParsableNumber(text)) {
                try {
                    return buildNumeric(key, text);
                } catch (RuntimeException ignored) {
                    return new KeyValueEntry(key, KeyValueType.STRING_V, false, text, 0L, 0D, null);
                }
            }
            return new KeyValueEntry(key, KeyValueType.STRING_V, false, text, 0L, 0D, null);
        }
        if (element.isObject() || element.isArray()) {
            return new KeyValueEntry(key, KeyValueType.JSON_V, false, null, 0L, 0D, element.toString());
        }
        throw new IllegalArgumentException(CAN_T_PARSE_VALUE + element);
    }

    private static KeyValueEntry buildNumeric(String key, String valueAsString) {
        BigDecimal bd = new BigDecimal(valueAsString);
        if (bd.stripTrailingZeros().scale() <= 0 && !isSimpleDouble(valueAsString)) {
            try {
                return new KeyValueEntry(key, KeyValueType.LONG_V, false, null, bd.longValueExact(), 0D, null);
            } catch (ArithmeticException e) {
                return new KeyValueEntry(key, KeyValueType.STRING_V, false, bd.toPlainString(), 0L, 0D, null);
            }
        }
        if (bd.scale() <= 16) {
            return new KeyValueEntry(key, KeyValueType.DOUBLE_V, false, null, 0L, bd.doubleValue(), null);
        }
        return new KeyValueEntry(key, KeyValueType.STRING_V, false, bd.toPlainString(), 0L, 0D, null);
    }

    private static boolean isParsableNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isSimpleDouble(String valueAsString) {
        return valueAsString.contains(".") && !valueAsString.contains("E") && !valueAsString.contains("e");
    }
}
