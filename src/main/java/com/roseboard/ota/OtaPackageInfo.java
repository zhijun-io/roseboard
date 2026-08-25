package com.roseboard.ota;

import java.util.UUID;

public record OtaPackageInfo(
        UUID id,
        UUID tenantId,
        UUID deviceProfileId,
        String kind,
        String title,
        String version,
        String tag,
        String fileName,
        String contentType,
        OtaArtifactSource artifactSource,
        String externalUrl,
        Long sizeBytes,
        String checksumAlgorithm,
        String checksumValue,
        OtaPackageStatus status,
        long createdAt,
        long recordVersion
) {
}
