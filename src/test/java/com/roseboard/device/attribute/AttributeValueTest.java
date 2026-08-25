package com.roseboard.device.attribute;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributeValueTest {
    @Test
    void acceptsSupportedBoundedValues() {
        assertDoesNotThrow(() -> new AttributeValue(Map.of(
                "enabled", true,
                "threshold", 12L,
                "labels", List.of("a", "b"))));
    }

    @Test
    void rejectsNonFiniteNumbersAndUnsupportedObjects() {
        assertThrows(IllegalArgumentException.class, () -> new AttributeValue(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new AttributeValue(new Object()));
    }

    @Test
    void rejectsInvalidKeys() {
        assertThrows(IllegalArgumentException.class, () -> new AttributeKey(""));
        assertThrows(IllegalArgumentException.class, () -> new AttributeKey("\u0000"));
    }
}
