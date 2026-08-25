package com.roseboard.ota;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OtaPackageCacheAuditContractTest {

    @Test
    void publicCatalogHasNoOidOrProtocolLeakage() {
        assertFalse(Arrays.stream(OtaPackageInfo.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("oid")));
        assertFalse(Arrays.stream(OtaPackageService.class.getMethods())
                .anyMatch(method -> method.getReturnType().getName().contains("JsonNode")));
    }

    @Test
    void packageTypesAreFirmwareAndSoftwareOnly() {
        assertEquals("firmware", OtaPackageType.FIRMWARE.kind());
        assertEquals("software", OtaPackageType.SOFTWARE.kind());
        assertEquals(OtaPackageType.FIRMWARE, OtaPackageType.require("firmware"));
        assertThrows(OtaPackageException.class, () -> OtaPackageType.require("custom"));
    }

    @Test
    void deviceAndProfileCarryTbStylePackageIds() throws Exception {
        assertFalse(com.roseboard.device.DeviceEntity.class.getDeclaredField("firmwareId") == null);
        assertFalse(com.roseboard.device.DeviceEntity.class.getDeclaredField("softwareId") == null);
        assertFalse(com.roseboard.device.profile.DeviceProfileEntity.class.getDeclaredField("firmwareId") == null);
        assertFalse(com.roseboard.device.profile.DeviceProfileEntity.class.getDeclaredField("softwareId") == null);
    }
}
