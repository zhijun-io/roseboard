package com.roseboard.infrastructure.transport;

public record KeyValueEntry(
        String key,
        KeyValueType type,
        boolean boolV,
        String stringV,
        long longV,
        double doubleV,
        String jsonV) {

    public Object toObject() {
        return switch (type) {
            case BOOLEAN_V -> boolV;
            case STRING_V -> stringV;
            case LONG_V -> longV;
            case DOUBLE_V -> doubleV;
            case JSON_V -> jsonV;
        };
    }
}
