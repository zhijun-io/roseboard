package com.roseboard.ota;

import java.util.Locale;
import java.util.UUID;

public record OtaPackageSaveRequest(
        UUID id,
        UUID deviceProfileId,
        String type,
        String title,
        String version,
        String tag,
        String url,
        Boolean usesUrl,
        String fileName,
        String contentType
) {
    String resolvedKind() {
        if (type == null || type.isBlank()) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "type required");
        }
        return type.strip().toLowerCase(Locale.ROOT);
    }

    boolean resolveUsesUrl() {
        return Boolean.TRUE.equals(usesUrl) || (url != null && !url.isBlank());
    }
}
