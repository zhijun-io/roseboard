package com.roseboard.ota;

import java.util.Locale;

/** ThingsBoard-aligned package types; stored as lowercase kind in ota_package.kind. */
public enum OtaPackageType {
    FIRMWARE,
    SOFTWARE;

    public String kind() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OtaPackageType require(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new OtaPackageException(OtaPackageErrorCode.UNKNOWN_KIND, "Package type required");
        }
        try {
            return OtaPackageType.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new OtaPackageException(OtaPackageErrorCode.UNKNOWN_KIND, "Unknown package type: " + kind);
        }
    }
}
