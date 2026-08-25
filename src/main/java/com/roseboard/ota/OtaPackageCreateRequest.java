package com.roseboard.ota;

import java.util.UUID;

public record OtaPackageCreateRequest(
        UUID tenantId,
        UUID deviceProfileId,
        String kind,
        String title,
        String version,
        String tag,
        OtaArtifactSource artifactSource,
        String externalUrl,
        String fileName,
        String contentType
) {
}
