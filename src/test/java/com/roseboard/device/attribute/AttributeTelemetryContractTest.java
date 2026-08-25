package com.roseboard.device.attribute;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.roseboard.device.telemetry.DeviceTelemetryStore;
import com.roseboard.device.telemetry.TelemetryService;

class AttributeTelemetryContractTest {
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "mybatis", "JsonNode", "Jsonb", "TypeHandler", "queue", "amqp", "mqtt",
            "Entity", "Mapper", "Jdbc", "PreparedStatement");

    @Test
    void publicStoresExposeDomainTypesOnly() {
        assertDomainSurface(DeviceAttributeStore.class);
        assertDomainSurface(DeviceTelemetryStore.class);
    }

    @Test
    void attributeAndTelemetryStoresRemainSeparate() {
        assertTrue(DeviceAttributeStore.class.isInterface());
        assertTrue(DeviceTelemetryStore.class.isInterface());
        assertFalse(DeviceAttributeStore.class.isAssignableFrom(DeviceTelemetryStore.class));
        assertFalse(DeviceTelemetryStore.class.isAssignableFrom(DeviceAttributeStore.class));
        // Single service implements each store surface (no separate Postgres* Store class).
        assertTrue(DeviceAttributeStore.class.isAssignableFrom(DeviceAttributeService.class));
        assertTrue(DeviceTelemetryStore.class.isAssignableFrom(TelemetryService.class));
    }

    private static void assertDomainSurface(Class<?> type) {
        for (Method method : type.getMethods()) {
            assertAllowedType(method.getReturnType(), method);
            for (Parameter parameter : method.getParameters()) {
                assertAllowedType(parameter.getType(), method);
            }
        }
    }

    private static void assertAllowedType(Class<?> candidate, Method method) {
        String name = candidate.getName();
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            assertFalse(name.contains(fragment),
                    () -> method + " exposes forbidden type " + name);
        }
    }
}
