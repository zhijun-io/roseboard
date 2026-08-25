package com.roseboard.infrastructure.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheEntryEncodingTest {

    @Test
    void encodesAndDecodesNullMarker() {
        assertThat(CacheEntryEncoding.isNullEntry(CacheEntryEncoding.encodeNull())).isTrue();
    }

    @Test
    void encodesAndDecodesValuePayload() {
        byte[] payload = new byte[]{9, 8, 7};
        assertThat(CacheEntryEncoding.decodePayload(CacheEntryEncoding.encodeValue(payload)))
                .containsExactly((byte) 9, (byte) 8, (byte) 7);
    }

    @Test
    void rejectsInvalidMarker() {
        assertThatThrownBy(() -> CacheEntryEncoding.decodePayload(new byte[]{2}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid cache entry marker");
    }
}
