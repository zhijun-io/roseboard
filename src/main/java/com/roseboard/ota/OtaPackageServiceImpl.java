package com.roseboard.ota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roseboard.common.PageData;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileMapper;
import com.roseboard.device.profile.DeviceProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class OtaPackageServiceImpl implements OtaPackageService {
    private final OtaPackageMapper packageMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceProfileMapper deviceProfileMapper;
    private final DeviceProfileService deviceProfileService;
    private final OtaArtifactStore artifactStore;
    private final long maxPackageBytes;
    private final long maxTenantBytes;
    private final Semaphore uploadPermits;

    public OtaPackageServiceImpl(OtaPackageMapper packageMapper,
                                 DeviceMapper deviceMapper,
                                 DeviceProfileMapper deviceProfileMapper,
                                 DeviceProfileService deviceProfileService,
                                 OtaArtifactStore artifactStore,
                                 @Value("${roseboard.ota.max-package-bytes:67108864}") long maxPackageBytes,
                                 @Value("${roseboard.ota.max-tenant-bytes:536870912}") long maxTenantBytes,
                                 @Value("${roseboard.ota.max-concurrent-uploads:4}") int maxConcurrentUploads) {
        this.packageMapper = packageMapper;
        this.deviceMapper = deviceMapper;
        this.deviceProfileMapper = deviceProfileMapper;
        this.deviceProfileService = deviceProfileService;
        this.artifactStore = artifactStore;
        this.maxPackageBytes = maxPackageBytes;
        this.maxTenantBytes = maxTenantBytes;
        this.uploadPermits = new Semaphore(Math.max(1, maxConcurrentUploads));
    }

    @Override
    @Transactional
    public OtaPackageInfo create(OtaPackageCreateRequest request) {
        validateCreate(request);
        OtaPackageType type = OtaPackageType.require(request.kind());
        if (request.artifactSource() != OtaArtifactSource.BINARY
                && request.artifactSource() != OtaArtifactSource.EXTERNAL_URL) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST,
                    "Unsupported artifact source: " + request.artifactSource());
        }
        requireSameTenantProfile(request.tenantId(), request.deviceProfileId());
        String externalUrl = null;
        OtaPackageStatus status = OtaPackageStatus.DRAFT;
        if (request.artifactSource() == OtaArtifactSource.EXTERNAL_URL) {
            validateExternalUrl(request.externalUrl());
            externalUrl = request.externalUrl().strip();
            status = OtaPackageStatus.PUBLISHED;
        } else if (request.externalUrl() != null && !request.externalUrl().isBlank()) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST,
                    "BINARY package cannot include external URL");
        }

        OtaPackageEntity entity = new OtaPackageEntity();
        entity.setId(UUID.randomUUID());
        entity.setCreatedTime(System.currentTimeMillis());
        entity.setTenantId(request.tenantId());
        entity.setDeviceProfileId(request.deviceProfileId());
        entity.setKind(type.kind());
        entity.setTitle(request.title().strip());
        entity.setVersion(request.version().strip());
        entity.setTag(blankToNull(request.tag()));
        entity.setFileName(blankToNull(request.fileName()));
        entity.setContentType(blankToNull(request.contentType()));
        entity.setArtifactSource(request.artifactSource().name());
        entity.setExternalUrl(externalUrl);
        entity.setStatus(status.name());
        entity.setRecordVersion(1L);
        try {
            packageMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new OtaPackageException(OtaPackageErrorCode.DUPLICATE_PACKAGE,
                    "Package already exists for tenant/kind/title/version");
        }
        return toInfo(entity);
    }

    /** Validates a Device/Profile firmwareId or softwareId reference (TB-style assignment). */
    public void requireAssignable(UUID tenantId, UUID packageId, OtaPackageType type) {
        if (packageId == null) {
            return;
        }
        OtaPackageEntity entity = requireEntity(tenantId, packageId);
        if (!type.kind().equals(entity.getKind())) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST,
                    "Package type mismatch: expected " + type.kind());
        }
        if (!isPublished(entity)) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST,
                    "Only published packages can be assigned");
        }
    }

    @Override
    public OtaPackageInfo findById(UUID tenantId, UUID packageId) {
        return toInfo(requireEntity(tenantId, packageId));
    }

    @Override
    public PageData<OtaPackageInfo> findPage(UUID tenantId, long pageSize, long page, String textSearch) {
        Page<OtaPackageEntity> result = packageMapper.selectPage(
                new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<OtaPackageEntity>()
                        .eq(OtaPackageEntity::getTenantId, tenantId)
                        .like(textSearch != null && !textSearch.isBlank(),
                                OtaPackageEntity::getTitle, textSearch)
                        .orderByDesc(OtaPackageEntity::getCreatedTime));
        return toPageData(result, pageSize, page);
    }

    @Override
    public PageData<OtaPackageInfo> findPageByProfileAndKind(UUID tenantId, UUID deviceProfileId, String kind,
                                                             long pageSize, long page, String textSearch) {
        OtaPackageType type = OtaPackageType.require(kind);
        Page<OtaPackageEntity> result = packageMapper.selectPage(
                new Page<>(page + 1L, pageSize),
                new LambdaQueryWrapper<OtaPackageEntity>()
                        .eq(OtaPackageEntity::getTenantId, tenantId)
                        .eq(OtaPackageEntity::getDeviceProfileId, deviceProfileId)
                        .eq(OtaPackageEntity::getKind, type.kind())
                        .eq(OtaPackageEntity::getStatus, OtaPackageStatus.PUBLISHED.name())
                        .like(textSearch != null && !textSearch.isBlank(),
                                OtaPackageEntity::getTitle, textSearch)
                        .orderByDesc(OtaPackageEntity::getCreatedTime));
        return toPageData(result, pageSize, page);
    }

    @Transactional
    public OtaPackageInfo uploadAndPublish(UUID tenantId, UUID packageId, InputStream input,
                                           String clientChecksum, String algorithm) {
        if (!uploadPermits.tryAcquire()) {
            throw new OtaPackageException(OtaPackageErrorCode.QUOTA_EXCEEDED, "Too many concurrent uploads");
        }
        try {
            OtaPackageEntity entity = requireEntity(tenantId, packageId);
            requireDraftBinary(entity);
            String normalizedAlgorithm = OtaArtifactStore.requireSupportedAlgorithm(algorithm);
            OtaArtifactStore.StoredArtifact stored = artifactStore.storeStreaming(
                    packageId, input, maxPackageBytes, normalizedAlgorithm);
            if (clientChecksum != null && !clientChecksum.isBlank()
                    && !clientChecksum.equalsIgnoreCase(stored.checksumValue())) {
                artifactStore.clear(packageId);
                throw new OtaPackageException(OtaPackageErrorCode.CHECKSUM_MISMATCH, "Checksum mismatch");
            }
            try {
                ensureTenantQuota(tenantId, stored.sizeBytes());
            } catch (OtaPackageException exception) {
                artifactStore.clear(packageId);
                throw exception;
            }
            entity.setSizeBytes(stored.sizeBytes());
            entity.setChecksumAlgorithm(stored.checksumAlgorithm());
            entity.setChecksumValue(stored.checksumValue());
            entity.setStatus(OtaPackageStatus.PUBLISHED.name());
            packageMapper.updateById(entity);
            return toInfo(entity);
        } finally {
            uploadPermits.release();
        }
    }

    public InputStream download(UUID tenantId, UUID packageId) {
        requirePublishedBinary(requireEntity(tenantId, packageId));
        return new ByteArrayInputStream(artifactStore.readAll(packageId));
    }

    public byte[] downloadRange(UUID tenantId, UUID packageId, long offset, long length) {
        OtaPackageEntity entity = requireEntity(tenantId, packageId);
        requirePublishedBinary(entity);
        return artifactStore.readRange(packageId, entity.getSizeBytes(), offset, length);
    }

    @Transactional
    public void delete(UUID tenantId, UUID packageId) {
        OtaPackageEntity entity = requireEntity(tenantId, packageId);
        if (isReferenced(packageId)) {
            throw new OtaPackageException(OtaPackageErrorCode.CONFLICT,
                    "Package is referenced by device or device profile");
        }
        artifactStore.clear(packageId);
        packageMapper.deleteById(entity.getId());
    }

    private boolean isReferenced(UUID packageId) {
        Long onDevices = deviceMapper.selectCount(new LambdaQueryWrapper<DeviceEntity>()
                .and(w -> w.eq(DeviceEntity::getFirmwareId, packageId)
                        .or()
                        .eq(DeviceEntity::getSoftwareId, packageId)));
        if (onDevices != null && onDevices > 0) {
            return true;
        }
        Long onProfiles = deviceProfileMapper.selectCount(new LambdaQueryWrapper<DeviceProfileEntity>()
                .and(w -> w.eq(DeviceProfileEntity::getFirmwareId, packageId)
                        .or()
                        .eq(DeviceProfileEntity::getSoftwareId, packageId)));
        return onProfiles != null && onProfiles > 0;
    }

    @Transactional
    public OtaPackageInfo updateMutableFields(UUID tenantId, UUID packageId, String title, String version, String tag) {
        OtaPackageEntity entity = requireEntity(tenantId, packageId);
        if (isPublished(entity)) {
            throw new OtaPackageException(OtaPackageErrorCode.IMMUTABLE_FIELD,
                    "Cannot modify kind/title/version/artifact of a published package");
        }
        if (title != null) {
            requireBounded(title, OtaPackageLimits.MAX_TITLE_CHARS, "title");
            entity.setTitle(title.strip());
        }
        if (version != null) {
            requireBounded(version, OtaPackageLimits.MAX_VERSION_CHARS, "version");
            entity.setVersion(version.strip());
        }
        entity.setTag(blankToNull(tag));
        packageMapper.updateById(entity);
        return toInfo(entity);
    }

    private OtaPackageEntity requireEntity(UUID tenantId, UUID packageId) {
        OtaPackageEntity entity = packageMapper.selectById(packageId);
        if (entity == null || !tenantId.equals(entity.getTenantId())) {
            throw new OtaPackageException(OtaPackageErrorCode.NOT_FOUND, "Package not found");
        }
        return entity;
    }

    private void requireSameTenantProfile(UUID tenantId, UUID deviceProfileId) {
        if (deviceProfileId == null) {
            return;
        }
        DeviceProfileEntity profile = deviceProfileService.findById(deviceProfileId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_PROFILE,
                    "Device profile must belong to the same tenant");
        }
    }

    private static void requireDraftBinary(OtaPackageEntity entity) {
        if (source(entity) != OtaArtifactSource.BINARY) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "Only BINARY packages accept upload");
        }
        if (isPublished(entity)) {
            throw new OtaPackageException(OtaPackageErrorCode.IMMUTABLE_FIELD, "Published package is immutable");
        }
    }

    private static void requirePublishedBinary(OtaPackageEntity entity) {
        if (!isPublished(entity) || source(entity) != OtaArtifactSource.BINARY) {
            throw new OtaPackageException(OtaPackageErrorCode.NOT_DOWNLOADABLE, "Package is not downloadable");
        }
    }

    private void ensureTenantQuota(UUID tenantId, long additionalBytes) {
        long used = packageMapper.selectList(new LambdaQueryWrapper<OtaPackageEntity>()
                        .eq(OtaPackageEntity::getTenantId, tenantId)
                        .eq(OtaPackageEntity::getStatus, OtaPackageStatus.PUBLISHED.name()))
                .stream()
                .mapToLong(entity -> entity.getSizeBytes() == null ? 0L : entity.getSizeBytes())
                .sum();
        if (used + additionalBytes > maxTenantBytes) {
            throw new OtaPackageException(OtaPackageErrorCode.QUOTA_EXCEEDED, "Tenant artifact quota exceeded");
        }
    }

    private static OtaPackageInfo toInfo(OtaPackageEntity entity) {
        return new OtaPackageInfo(
                entity.getId(),
                entity.getTenantId(),
                entity.getDeviceProfileId(),
                entity.getKind(),
                entity.getTitle(),
                entity.getVersion(),
                entity.getTag(),
                entity.getFileName(),
                entity.getContentType(),
                source(entity),
                entity.getExternalUrl() == null ? null : OtaUrlSanitizer.redact(entity.getExternalUrl()),
                entity.getSizeBytes(),
                entity.getChecksumAlgorithm(),
                entity.getChecksumValue(),
                OtaPackageStatus.valueOf(entity.getStatus()),
                entity.getCreatedTime(),
                entity.getRecordVersion());
    }

    private static PageData<OtaPackageInfo> toPageData(Page<OtaPackageEntity> result, long pageSize, long page) {
        return new PageData<>(
                result.getRecords().stream().map(OtaPackageServiceImpl::toInfo).toList(),
                pageSize,
                page,
                result.getTotal());
    }

    private static void validateCreate(OtaPackageCreateRequest request) {
        if (request == null || request.tenantId() == null || request.artifactSource() == null) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "Invalid create request");
        }
        requireBounded(request.title(), OtaPackageLimits.MAX_TITLE_CHARS, "title");
        requireBounded(request.version(), OtaPackageLimits.MAX_VERSION_CHARS, "version");
        requireOptionalBound(request.tag(), OtaPackageLimits.MAX_TAG_CHARS, "tag");
        requireOptionalBound(request.fileName(), OtaPackageLimits.MAX_FILE_NAME_CHARS, "fileName");
        requireOptionalBound(request.contentType(), OtaPackageLimits.MAX_CONTENT_TYPE_CHARS, "contentType");
    }

    private static void validateExternalUrl(String url) {
        if (url == null || url.isBlank() || url.length() > OtaPackageLimits.MAX_URL_CHARS) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_URL, "Invalid external URL");
        }
        String scheme = url.strip().split(":", 2)[0].toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_URL, "URL scheme not allowed: " + scheme);
        }
    }

    private static void requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "Invalid " + field);
        }
    }

    private static void requireOptionalBound(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, field + " too long");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean isPublished(OtaPackageEntity entity) {
        return OtaPackageStatus.PUBLISHED.name().equals(entity.getStatus());
    }

    private static OtaArtifactSource source(OtaPackageEntity entity) {
        return OtaArtifactSource.valueOf(entity.getArtifactSource());
    }
}
