package com.roseboard.infrastructure.cache;

import java.util.Arrays;

/** Wire format for cache-aside entries: null marker or versioned payload bytes. */
final class CacheEntryEncoding {
    private static final byte NULL_MARKER = 0;
    private static final byte VALUE_MARKER = 1;

    private CacheEntryEncoding() {
    }

    static byte[] encodeNull() {
        return new byte[]{NULL_MARKER};
    }

    static byte[] encodeValue(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Cache codec returned null");
        }
        byte[] result = new byte[payload.length + 1];
        result[0] = VALUE_MARKER;
        System.arraycopy(payload, 0, result, 1, payload.length);
        return result;
    }

    static boolean isNullEntry(byte[] raw) {
        return raw.length == 1 && raw[0] == NULL_MARKER;
    }

    static byte[] decodePayload(byte[] raw) {
        if (isNullEntry(raw)) {
            throw new IllegalArgumentException("Cache entry is null marker");
        }
        if (raw.length < 2 || raw[0] != VALUE_MARKER) {
            throw new IllegalArgumentException("Invalid cache entry marker");
        }
        return Arrays.copyOfRange(raw, 1, raw.length);
    }
}
