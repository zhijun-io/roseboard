package com.roseboard.common;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.CollectionType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Framework-neutral JSON utility facade, migrated from ThingsBoard
 * {@code org.thingsboard.common.util.JacksonUtil} (Jackson 2 -> Jackson 3).
 *
 * <p>把 tb 的 JacksonUtil 全部公开方法迁移到本类；依赖 ThingsBoard 领域模型的方法除外：
 * {@code writeValueAsViewIgnoringNullFields}（依赖 tb Views）与 {@code addKvEntry}
 * （依赖 tb KvEntry/DataType）在迁移到对应领域模型后补充。
 * {@code replaceUuidsRecursively} 的 tb RegexUtils 依赖以内联 UUID 正则替代。</p>
 */
public final class JacksonUtils {

    public static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    public static final ObjectMapper PRETTY_SORTED_JSON_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();
    public static final ObjectMapper IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    public static final ObjectMapper CANONICAL_JSON_MAPPER = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private JacksonUtils() {
    }

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        try {
            return fromValue != null ? OBJECT_MAPPER.convertValue(fromValue, toValueType) : null;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The given object value cannot be converted to " + toValueType + ": " + fromValue, e);
        }
    }

    public static <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        try {
            return fromValue != null ? OBJECT_MAPPER.convertValue(fromValue, toValueTypeRef) : null;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The given object value cannot be converted to " + toValueTypeRef + ": " + fromValue, e);
        }
    }

    public static <T> T fromString(String string, Class<T> clazz) {
        return fromString(string, clazz,
                "The given string value cannot be transformed to Json object: " + string);
    }

    public static <T> T fromString(String string, Class<T> clazz, String errorMsg) {
        try {
            return string != null ? OBJECT_MAPPER.readValue(string, clazz) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(errorMsg, e);
        }
    }

    public static <T> T fromString(String string, TypeReference<T> valueTypeRef) {
        try {
            return string != null ? OBJECT_MAPPER.readValue(string, valueTypeRef) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given string value cannot be transformed to Json object: " + string, e);
        }
    }

    public static <T> T fromString(String string, JavaType javaType) {
        try {
            return string != null ? OBJECT_MAPPER.readValue(string, javaType) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given String value cannot be transformed to Json object: " + string, e);
        }
    }

    public static <T> T fromString(String string, Class<T> clazz, boolean ignoreUnknownFields) {
        try {
            return string != null ? IGNORE_UNKNOWN_PROPERTIES_JSON_MAPPER.readValue(string, clazz) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given string value cannot be transformed to Json object: " + string, e);
        }
    }

    public static <T> T fromBytes(byte[] bytes, Class<T> clazz) {
        try {
            return bytes != null ? OBJECT_MAPPER.readValue(bytes, clazz) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given byte[] value cannot be transformed to Json object: " + Arrays.toString(bytes), e);
        }
    }

    public static <T> T fromBytes(byte[] bytes, TypeReference<T> valueTypeRef) {
        try {
            return bytes != null ? OBJECT_MAPPER.readValue(bytes, valueTypeRef) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given string value cannot be transformed to Json object: " + Arrays.toString(bytes), e);
        }
    }

    public static JsonNode fromBytes(byte[] bytes) {
        try {
            return OBJECT_MAPPER.readTree(bytes);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given byte[] value cannot be transformed to Json object: " + Arrays.toString(bytes), e);
        }
    }

    public static String toString(Object value) {
        try {
            return value != null ? OBJECT_MAPPER.writeValueAsString(value) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given Json object value cannot be transformed to a String: " + value, e);
        }
    }

    public static String writeValueAsString(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given Json object value: " + value + " cannot be transformed to a String", e);
        }
    }

    public static String toPrettyString(Object value) {
        try {
            return PRETTY_SORTED_JSON_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given Json object value cannot be transformed to a pretty string: " + value, e);
        }
    }

    public static String toPlainText(String data) {
        if (data == null) {
            return null;
        }
        if (data.startsWith("\"") && data.endsWith("\"") && data.length() >= 2) {
            String dataBefore = data;
            try {
                data = JacksonUtils.fromString(data, String.class);
            } catch (Exception ignored) {
                // keep original
            }
            if (dataBefore.equals(data)) {
                return dataBefore;
            }
        }
        return data;
    }

    public static String toCanonicalString(Object value) {
        try {
            if (value == null) {
                return null;
            }
            if (value instanceof JsonNode) {
                Object pojo = CANONICAL_JSON_MAPPER.convertValue(value, Object.class);
                return CANONICAL_JSON_MAPPER.writeValueAsString(pojo);
            }
            return CANONICAL_JSON_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The given Json object value cannot be transformed to a canonical String: " + value, e);
        }
    }

    public static <T> T treeToValue(JsonNode node, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.treeToValue(node, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't convert value: " + node, e);
        }
    }

    public static <T> T treeToValue(JsonNode node, TypeReference<T> type) {
        try {
            return OBJECT_MAPPER.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't convert value: " + node, e);
        }
    }

    public static JsonNode toJsonNode(String value) {
        return toJsonNode(value, OBJECT_MAPPER);
    }

    public static JsonNode toJsonNode(String value, ObjectMapper mapper) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T readValue(String object, CollectionType clazz) {
        try {
            return OBJECT_MAPPER.readValue(object, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't read object: " + object, e);
        }
    }

    public static <T> T readValue(Reader reader, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(reader, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't read object: " + reader, e);
        }
    }

    public static <T> T readValue(String object, TypeReference<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(object, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't read object: " + object, e);
        }
    }

    public static <T> T readValue(File file, TypeReference<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(file, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't read file: " + file, e);
        }
    }

    public static <T> T readValue(File file, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(file, clazz);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Can't read file: " + file, e);
        }
    }

    public static JsonNode toJsonNode(Path file) {
        try {
            return OBJECT_MAPPER.readTree(Files.readAllBytes(file));
        } catch (IOException | JacksonException e) {
            throw new IllegalArgumentException("Can't read file: " + file, e);
        }
    }

    public static JsonNode toJsonNode(File value) {
        try {
            return value != null ? OBJECT_MAPPER.readTree(value) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given File object value: " + value + " cannot be transformed to a JsonNode", e);
        }
    }

    public static JsonNode toJsonNode(InputStream value) {
        try {
            return value != null ? OBJECT_MAPPER.readTree(value) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given InputStream value: " + value + " cannot be transformed to a JsonNode", e);
        }
    }

    public static ObjectNode newObjectNode() {
        return newObjectNode(OBJECT_MAPPER);
    }

    public static ObjectNode newObjectNode(ObjectMapper mapper) {
        return mapper.createObjectNode();
    }

    public static ArrayNode newArrayNode() {
        return newArrayNode(OBJECT_MAPPER);
    }

    public static ArrayNode newArrayNode(ObjectMapper mapper) {
        return mapper.createArrayNode();
    }

    public static <T> T clone(T value) {
        @SuppressWarnings("unchecked")
        Class<T> valueClass = (Class<T>) value.getClass();
        return fromString(toString(value), valueClass);
    }

    public static <T> JsonNode valueToTree(T value) {
        return OBJECT_MAPPER.valueToTree(value);
    }

    public static byte[] writeValueAsBytes(Object value) {
        try {
            return value != null ? OBJECT_MAPPER.writeValueAsBytes(value) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given Json object value cannot be transformed to bytes: " + value, e);
        }
    }

    public static JsonNode getSafely(JsonNode node, String... path) {
        if (node == null) {
            return null;
        }
        for (String p : path) {
            if (!node.has(p)) {
                return null;
            }
            node = node.get(p);
        }
        return node;
    }

    public static ObjectNode asObject(JsonNode node) {
        return node != null && node.isObject() ? ((ObjectNode) node) : newObjectNode();
    }

    public static void putNestedId(ObjectNode object, String target, String source) {
        JsonNode value = object.get(target);
        if (value == null) {
            value = object.get(source);
        }
        if (value != null && value.isObject() && value.has("id")) {
            object.put(target, value.get("id").asText());
        }
    }

    public static <T> T fromReader(Reader reader, Class<T> clazz) {
        try {
            return reader != null ? OBJECT_MAPPER.readValue(reader, clazz) : null;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid request payload", e);
        }
    }

    public static <T> void writeValue(Writer writer, T value) {
        try {
            OBJECT_MAPPER.writeValue(writer, value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given writer value: " + writer + " cannot be written", e);
        }
    }

    public static <T> void writeValue(OutputStream outputStream, T value) {
        try {
            OBJECT_MAPPER.writeValue(outputStream, value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "The given writer value: " + outputStream + " cannot be written", e);
        }
    }

    public static JavaType constructCollectionType(Class<? extends Collection> collectionClass,
                                                   Class<?> elementClass) {
        return OBJECT_MAPPER.getTypeFactory().constructCollectionType(collectionClass, elementClass);
    }

    public static Map<String, String> toFlatMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        toFlatMap(node, "", map);
        return map;
    }

    private static void toFlatMap(JsonNode node, String currentPath, Map<String, String> map) {
        if (node.isObject()) {
            currentPath = currentPath.isEmpty() ? "" : currentPath + ".";
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                toFlatMap(entry.getValue(), currentPath + entry.getKey(), map);
            }
        } else if (node.isValueNode()) {
            map.put(currentPath, node.asText());
        }
    }

    /**
     * 递归替换 JSON 文本节点内嵌的 UUID；tb RegexUtils 依赖以内联正则替代。
     */
    public static void replaceUuidsRecursively(JsonNode node, Set<String> skippedRootFields,
                                               Pattern includedFieldsPattern, UnaryOperator<UUID> replacer,
                                               boolean root) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> property : objectNode.properties()) {
                String fieldName = property.getKey();
                if (root && skippedRootFields.contains(fieldName)) {
                    continue;
                }
                JsonNode child = objectNode.get(fieldName);
                if (child.isObject() || child.isArray()) {
                    replaceUuidsRecursively(child, skippedRootFields, includedFieldsPattern, replacer, false);
                } else if (child.isTextual()) {
                    if (includedFieldsPattern != null && !includedFieldsPattern.matcher(fieldName).matches()) {
                        continue;
                    }
                    String text = child.asText();
                    String newText = replaceUuids(text, replacer);
                    if (!text.equals(newText)) {
                        objectNode.put(fieldName, newText);
                    }
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode arrayElement = array.get(i);
                if (arrayElement.isObject() || arrayElement.isArray()) {
                    replaceUuidsRecursively(arrayElement, skippedRootFields, includedFieldsPattern, replacer, false);
                } else if (arrayElement.isTextual()) {
                    String text = arrayElement.asText();
                    String newText = replaceUuids(text, replacer);
                    if (!text.equals(newText)) {
                        array.set(i, newText);
                    }
                }
            }
        }
    }

    private static String replaceUuids(String text, UnaryOperator<UUID> replacer) {
        java.util.regex.Matcher matcher = UUID_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    java.util.regex.Matcher.quoteReplacement(replacer.apply(UUID.fromString(matcher.group())).toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 对所有文本节点应用处理器（含路径前缀）；迁移自 tb replaceAll。
     */
    public static void replaceAll(JsonNode root, String pathPrefix, BiFunction<String, String, String> processor) {
        Queue<JsonNodeProcessingTask> tasks = new LinkedList<>();
        tasks.add(new JsonNodeProcessingTask(pathPrefix, root));
        while (!tasks.isEmpty()) {
            JsonNodeProcessingTask task = tasks.poll();
            JsonNode node = task.node();
            if (node == null) {
                continue;
            }
            String currentPath = task.path().isBlank() ? "" : (task.path() + ".");
            if (node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                for (Map.Entry<String, JsonNode> property : objectNode.properties()) {
                    String childName = property.getKey();
                    JsonNode childValue = property.getValue();
                    if (childValue.isTextual()) {
                        objectNode.put(childName, processor.apply(currentPath + childName, childValue.asText()));
                    } else if (childValue.isObject() || childValue.isArray()) {
                        tasks.add(new JsonNodeProcessingTask(currentPath + childName, childValue));
                    }
                }
            } else if (node.isArray()) {
                ArrayNode childArray = (ArrayNode) node;
                for (int i = 0; i < childArray.size(); i++) {
                    JsonNode element = childArray.get(i);
                    if (element.isObject()) {
                        tasks.add(new JsonNodeProcessingTask(currentPath + "." + i, element));
                    } else if (element.isTextual()) {
                        childArray.set(i, processor.apply(currentPath + "." + i, element.asText()));
                    }
                }
            }
        }
    }

    public static void replaceAllByMapping(JsonNode jsonNode, Map<String, String> mapping,
                                           Map<String, String> templateParams,
                                           BiFunction<String, String, String> processor) {
        replaceByMapping(jsonNode, mapping, templateParams, (name, value) -> {
            if (value.isTextual()) {
                return valueToTree(processor.apply(name, value.asText()));
            } else if (value.isArray()) {
                ArrayNode array = (ArrayNode) value;
                for (int i = 0; i < array.size(); i++) {
                    String arrayElementName = name.replace("$index", Integer.toString(i));
                    array.set(i, processor.apply(arrayElementName, array.get(i).asText()));
                }
                return array;
            }
            return value;
        });
    }

    public static void replaceByMapping(JsonNode jsonNode, Map<String, String> mapping,
                                        Map<String, String> templateParams,
                                        BiFunction<String, JsonNode, JsonNode> processor) {
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String expression = entry.getValue();
            Queue<JsonPathProcessingTask> tasks = new LinkedList<>();
            tasks.add(new JsonPathProcessingTask(entry.getKey().split("\\."), templateParams, jsonNode));
            while (!tasks.isEmpty()) {
                JsonPathProcessingTask task = tasks.poll();
                String token = task.currentToken();
                JsonNode node = task.node();
                if (node == null) {
                    continue;
                }
                if (token.equals("*") || token.startsWith("$")) {
                    String variableName = token.startsWith("$") ? token.substring(1) : null;
                    if (node.isArray()) {
                        ArrayNode childArray = (ArrayNode) node;
                        for (JsonNode element : childArray) {
                            tasks.add(task.next(element));
                        }
                    } else if (node.isObject()) {
                        ObjectNode objectNode = (ObjectNode) node;
                        for (Map.Entry<String, JsonNode> kv : objectNode.properties()) {
                            if (variableName != null) {
                                tasks.add(task.next(kv.getValue(), variableName, kv.getKey()));
                            } else {
                                tasks.add(task.next(kv.getValue()));
                            }
                        }
                    }
                } else {
                    String variableName = null;
                    String variableValue = null;
                    if (token.contains("[$")) {
                        variableName = token.substring(token.indexOf("[$") + 2, token.indexOf(']'));
                        token = token.substring(0, token.indexOf("[$"));
                    }
                    if (node.has(token)) {
                        JsonNode value = node.get(token);
                        if (variableName != null && value.has(variableName) && value.get(variableName).isTextual()) {
                            variableValue = value.get(variableName).asText();
                        }
                        if (task.isLast()) {
                            String name = expression;
                            for (Map.Entry<String, String> replacement : task.variables().entrySet()) {
                                name = name.replace("$" + replacement.getKey(),
                                        replacement.getValue() == null ? "" : replacement.getValue());
                            }
                            ((ObjectNode) node).set(token, processor.apply(name, value));
                        } else {
                            if (variableName != null && !variableName.isEmpty()) {
                                tasks.add(task.next(value, variableName, variableValue));
                            } else {
                                tasks.add(task.next(value));
                            }
                        }
                    }
                }
            }
        }
    }

    public record JsonNodeProcessingTask(String path, JsonNode node) {
    }

    public record JsonPathProcessingTask(String[] tokens, Map<String, String> variables, JsonNode node) {
        public boolean isLast() {
            return tokens.length == 1;
        }

        public String currentToken() {
            return tokens[0];
        }

        public JsonPathProcessingTask next(JsonNode next) {
            return new JsonPathProcessingTask(
                    Arrays.copyOfRange(tokens, 1, tokens.length),
                    variables,
                    next);
        }

        public JsonPathProcessingTask next(JsonNode next, String key, String value) {
            Map<String, String> updated = new HashMap<>(variables);
            updated.put(key, value);
            return new JsonPathProcessingTask(
                    Arrays.copyOfRange(tokens, 1, tokens.length),
                    updated,
                    next);
        }
    }
}