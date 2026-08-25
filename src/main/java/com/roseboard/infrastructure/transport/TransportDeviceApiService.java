package com.roseboard.infrastructure.transport;

import com.roseboard.audit.AuditActions;
import com.roseboard.common.security.SecurityUsers;
import com.roseboard.infrastructure.audit.event.AuditEvent;
import com.roseboard.infrastructure.audit.event.AuditStatus;
import com.roseboard.infrastructure.audit.event.AuditTarget;
import com.roseboard.infrastructure.audit.event.EntityType;
import com.roseboard.device.DeviceEntity;
import com.roseboard.device.DeviceMapper;
import com.roseboard.device.DeviceService;
import com.roseboard.device.attribute.AttributeKey;
import com.roseboard.device.attribute.AttributeScope;
import com.roseboard.device.attribute.AttributeValue;
import com.roseboard.device.attribute.DeviceAttribute;
import com.roseboard.device.attribute.DeviceAttributeService;
import com.roseboard.device.credential.DeviceCredentialEntity;
import com.roseboard.device.credential.DeviceCredentialService;
import com.roseboard.device.credential.DeviceCredentialType;
import com.roseboard.device.profile.DeviceProfileEntity;
import com.roseboard.device.profile.DeviceProfileMapper;
import com.roseboard.ota.OtaArtifactSource;
import com.roseboard.ota.OtaPackageInfo;
import com.roseboard.ota.OtaPackageService;
import com.roseboard.ota.OtaPackageServiceImpl;
import com.roseboard.ota.OtaPackageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransportDeviceApiService {
    private static final String CLAIMING_ALLOWED = "claimingAllowed";
    private static final String CLAIMING_DATA = "claimingData";
    private static final String PROVISION_STATE = "provisionState";
    private static final String PROVISIONED_STATE = "provisioned";
    private static final String X509_CERTIFICATE_CHAIN = "X509_CERTIFICATE_CHAIN";

    private final DeviceService deviceService;
    private final DeviceMapper deviceMapper;
    private final DeviceProfileMapper profileMapper;
    private final DeviceAttributeService attributes;
    private final DeviceCredentialService credentialsService;
    private final OtaPackageService otaPackageService;
    private final OtaPackageServiceImpl otaPackages;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean allowClaimingByDefault;
    private final long defaultClaimDurationMs;

    public TransportDeviceApiService(DeviceService deviceService,
                                     DeviceMapper deviceMapper,
                                     DeviceProfileMapper profileMapper,
                                     DeviceAttributeService attributes,
                                     DeviceCredentialService credentialsService,
                                     OtaPackageService otaPackageService,
                                     OtaPackageServiceImpl otaPackages,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${roseboard.claim.allow-by-default:true}") boolean allowClaimingByDefault,
                                     @Value("${roseboard.claim.duration-ms:86400000}") long defaultClaimDurationMs) {
        this.deviceService = deviceService;
        this.deviceMapper = deviceMapper;
        this.profileMapper = profileMapper;
        this.attributes = attributes;
        this.credentialsService = credentialsService;
        this.otaPackageService = otaPackageService;
        this.otaPackages = otaPackages;
        this.eventPublisher = eventPublisher;
        this.allowClaimingByDefault = allowClaimingByDefault;
        this.defaultClaimDurationMs = defaultClaimDurationMs;
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerClaimingInfo(UUID tenantId, UUID deviceId, String json) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new IllegalStateException("Device not found");
        }
        if (device.getCustomerId() != null) {
            throw new IllegalArgumentException("Device already claimed");
        }
        if (!allowClaimingByDefault && !claimingAllowed(tenantId, deviceId)) {
            throw new IllegalArgumentException("claimingAllowed attribute required");
        }
        JsonConverter.ClaimRequest claim = JsonConverter.parseClaimRequest(json);
        long expirationTime = System.currentTimeMillis()
                + (claim.durationMs() > 0L ? claim.durationMs() : defaultClaimDurationMs);
        upsertServerAttribute(tenantId, deviceId, CLAIMING_DATA, Map.of(
                "secretKey", claim.secretKey(),
                "expirationTime", expirationTime));
        publishDeviceEvent(AuditActions.DEVICE_CLAIM_REGISTERED, tenantId, deviceId, AuditStatus.SUCCEEDED, null);
    }

    /**
     * TB {@code validateOrCreateDeviceX509Certificate}: authenticate existing cert or provision via chain profile.
     */
    @Transactional(rollbackFor = Exception.class)
    public DeviceCredentialService.DevicePrincipal resolveX509CertificateChain(String certificateChain) {
        List<String> chain = X509CertificateUtil.splitCertificateChain(certificateChain);
        if (chain.isEmpty()) {
            return null;
        }
        String leafCertificatePem = chain.getFirst();
        for (String certificatePem : chain) {
            String certificateHash = X509CertificateUtil.sha3HashHex(certificatePem);
            DeviceCredentialService.DevicePrincipal existing = credentialsService.authenticateX509Certificate(certificateHash);
            if (existing != null) {
                return existing;
            }
            DeviceProfileEntity profile = profileMapper.findByProvisionDeviceKey(certificateHash);
            if (profile == null || profile.getProfileData() == null) {
                continue;
            }
            JsonNode configuration = profile.getProfileData().get("provisionConfiguration");
            if (configuration == null || !X509_CERTIFICATE_CHAIN.equals(text(configuration, "type"))) {
                continue;
            }
            DeviceCredentialService.DevicePrincipal provisioned =
                    provisionViaX509Chain(profile, configuration, leafCertificatePem);
            if (provisioned != null) {
                return provisioned;
            }
        }
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<String> provision(String json) {
        JsonNode body = JsonConverter.parseProvisionRequest(json);
        String provisionKey = text(body, "provisionDeviceKey");
        String provisionSecret = text(body, "provisionDeviceSecret");
        if (!StringUtils.hasText(provisionKey) || !StringUtils.hasText(provisionSecret)) {
            publishDeviceEvent(AuditActions.DEVICE_PROVISIONED, null, null, AuditStatus.FAILED, "Missing provision key");
            return provisionResponse(ProvisionStatus.NOT_FOUND);
        }
        DeviceProfileEntity profile = profileMapper.findByProvisionDeviceKey(provisionKey);
        JsonNode configuration = profile == null || profile.getProfileData() == null
                ? null
                : profile.getProfileData().get("provisionConfiguration");
        if (configuration == null || !provisionSecret.equals(text(configuration, "provisionDeviceSecret"))) {
            publishDeviceEvent(AuditActions.DEVICE_PROVISIONED, profile == null ? null : profile.getTenantId(),
                    null, AuditStatus.FAILED, "Invalid provision credentials");
            return provisionResponse(ProvisionStatus.NOT_FOUND);
        }
        String strategy = text(configuration, "type");
        String deviceName = normalizeDeviceName(text(body, "deviceName"));
        if (deviceName != null && deviceName.isEmpty()) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        return switch (strategy == null ? "" : strategy) {
            case "ALLOW_CREATE_NEW_DEVICES" -> provisionAllowCreate(profile, deviceName);
            case "CHECK_PRE_PROVISIONED_DEVICES" -> provisionPreProvisioned(profile, deviceName);
            case X509_CERTIFICATE_CHAIN -> provisionResponse(ProvisionStatus.NOT_FOUND);
            default -> provisionResponse(ProvisionStatus.NOT_FOUND);
        };
    }

    private DeviceCredentialService.DevicePrincipal provisionViaX509Chain(DeviceProfileEntity profile,
                                                                          JsonNode configuration,
                                                                          String leafCertificatePem) {
        X509Certificate certificate = X509CertificateUtil.readCertificate(leafCertificatePem);
        String commonName = X509CertificateUtil.parseCommonName(certificate);
        String deviceName = X509CertificateUtil.extractDeviceName(commonName, text(configuration, "certificateRegExPattern"));
        if (!StringUtils.hasText(deviceName)) {
            return null;
        }
        DeviceEntity device = deviceMapper.findByTenantAndName(profile.getTenantId(), deviceName);
        if (device != null && profile.getId().equals(device.getDeviceProfileId())) {
            DeviceCredentialEntity credentials = credentialsService.findByDeviceId(device.getId());
            if (credentials != null && DeviceCredentialType.X509_CERTIFICATE.name().equals(credentials.getCredentialsType())) {
                credentialsService.save(profile.getTenantId(), device.getId(),
                        DeviceCredentialType.X509_CERTIFICATE,
                        X509CertificateUtil.sha3HashHex(leafCertificatePem), credentialsBody(leafCertificatePem));
                return credentialsService.authenticateX509Certificate(X509CertificateUtil.sha3HashHex(leafCertificatePem));
            }
            return null;
        }
        if (!configuration.path("allowCreateNewDevicesByX509Certificate").asBoolean(false)) {
            return null;
        }
        return provisionX509Device(profile, deviceName, leafCertificatePem);
    }

    private DeviceCredentialService.DevicePrincipal provisionX509Device(DeviceProfileEntity profile,
                                                                        String deviceName,
                                                                        String leafCertificatePem) {
        if (deviceMapper.findByTenantAndName(profile.getTenantId(), deviceName) != null) {
            return null;
        }
        DeviceEntity device = new DeviceEntity();
        device.setTenantId(profile.getTenantId());
        device.setDeviceProfileId(profile.getId());
        device.setName(deviceName);
        device.setType("default");
        deviceService.save(device);
        String hash = X509CertificateUtil.sha3HashHex(leafCertificatePem);
        credentialsService.save(profile.getTenantId(), device.getId(),
                DeviceCredentialType.X509_CERTIFICATE, hash, credentialsBody(leafCertificatePem));
        markProvisioned(profile.getTenantId(), device.getId());
        return credentialsService.authenticateX509Certificate(hash);
    }

    private static String credentialsBody(String leafCertificatePem) {
        return "{\"fingerprint\":\"" + X509CertificateUtil.sha3HashHex(leafCertificatePem) + "\"}";
    }

    public ResponseEntity<byte[]> downloadOta(UUID tenantId, UUID deviceId, UUID deviceProfileId,
                                              OtaPackageType type, String title, String version,
                                              int chunkSize, int chunk) {
        DeviceEntity device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            return ResponseEntity.notFound().build();
        }
        UUID packageId = type == OtaPackageType.FIRMWARE ? device.getFirmwareId() : device.getSoftwareId();
        if (packageId == null) {
            DeviceProfileEntity profile = profileMapper.selectById(deviceProfileId);
            if (profile == null) {
                return ResponseEntity.notFound().build();
            }
            packageId = type == OtaPackageType.FIRMWARE ? profile.getFirmwareId() : profile.getSoftwareId();
        }
        if (packageId == null) {
            return ResponseEntity.notFound().build();
        }
        OtaPackageInfo pkg = otaPackageService.findById(tenantId, packageId);
        if (pkg.artifactSource() == OtaArtifactSource.EXTERNAL_URL) {
            return ResponseEntity.notFound().build();
        }
        if (!title.equals(pkg.title()) || !version.equals(pkg.version())) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes = chunkSize <= 0
                ? readAllBytes(otaPackages.download(tenantId, packageId))
                : otaPackages.downloadRange(tenantId, packageId, (long) chunkSize * chunk, chunkSize);
        String fileName = pkg.fileName() == null ? "package.bin" : pkg.fileName();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileName)
                .header("x-filename", fileName)
                .contentLength(bytes.length)
                .contentType(parseMediaType(pkg.contentType()))
                .body(bytes);
    }

    private ResponseEntity<String> provisionAllowCreate(DeviceProfileEntity profile, String deviceName) {
        if (StringUtils.hasText(deviceName) && deviceMapper.findByTenantAndName(profile.getTenantId(), deviceName) != null) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        DeviceEntity device = new DeviceEntity();
        device.setTenantId(profile.getTenantId());
        device.setDeviceProfileId(profile.getId());
        device.setName(StringUtils.hasText(deviceName) ? deviceName : "provisioned-" + UUID.randomUUID());
        device.setType("default");
        deviceService.save(device);
        DeviceCredentialEntity credentials = credentialsService.generateAccessToken(device.getId());
        markProvisioned(profile.getTenantId(), device.getId());
        publishDeviceEvent(AuditActions.DEVICE_PROVISIONED, profile.getTenantId(), device.getId(),
                AuditStatus.SUCCEEDED, null);
        return provisionResponse(ProvisionStatus.SUCCESS, credentials.getCredentialsType(), credentials.getCredentialsValue());
    }

    private ResponseEntity<String> provisionPreProvisioned(DeviceProfileEntity profile, String deviceName) {
        if (!StringUtils.hasText(deviceName)) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        DeviceEntity device = deviceMapper.findByTenantAndName(profile.getTenantId(), deviceName);
        if (device == null || !profile.getId().equals(device.getDeviceProfileId())) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        if (alreadyProvisioned(profile.getTenantId(), device.getId())) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        DeviceCredentialEntity credentials = credentialsService.findByDeviceId(device.getId());
        if (credentials == null) {
            return provisionResponse(ProvisionStatus.FAILURE);
        }
        markProvisioned(profile.getTenantId(), device.getId());
        publishDeviceEvent(AuditActions.DEVICE_PROVISIONED, profile.getTenantId(), device.getId(),
                AuditStatus.SUCCEEDED, null);
        return provisionResponse(ProvisionStatus.SUCCESS, credentials.getCredentialsType(), credentials.getCredentialsId());
    }

    private void markProvisioned(UUID tenantId, UUID deviceId) {
        upsertServerAttribute(tenantId, deviceId, PROVISION_STATE, PROVISIONED_STATE);
    }

    private boolean alreadyProvisioned(UUID tenantId, UUID deviceId) {
        DeviceAttribute state = attributes.find(tenantId, deviceId, AttributeScope.SERVER, new AttributeKey(PROVISION_STATE));
        return state != null && PROVISIONED_STATE.equals(String.valueOf(state.value().value()));
    }

    private boolean claimingAllowed(UUID tenantId, UUID deviceId) {
        DeviceAttribute allowed = attributes.find(tenantId, deviceId, AttributeScope.SERVER, new AttributeKey(CLAIMING_ALLOWED));
        return allowed != null && Boolean.TRUE.equals(allowed.value().value());
    }

    private void upsertServerAttribute(UUID tenantId, UUID deviceId, String key, Object value) {
        AttributeKey attributeKey = new AttributeKey(key);
        AttributeValue attributeValue = new AttributeValue(value);
        DeviceAttribute existing = attributes.find(tenantId, deviceId, AttributeScope.SERVER, attributeKey);
        if (existing == null) {
            attributes.save(tenantId, deviceId, AttributeScope.SERVER, attributeKey, attributeValue);
        } else {
            attributes.update(tenantId, deviceId, AttributeScope.SERVER, attributeKey, attributeValue, existing.version());
        }
    }

    private static String normalizeDeviceName(String deviceName) {
        if (!StringUtils.hasText(deviceName)) {
            return null;
        }
        return deviceName.trim();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static MediaType parseMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return MediaType.parseMediaType(contentType);
    }

    private static byte[] readAllBytes(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read OTA package");
        }
    }

    private static ResponseEntity<String> provisionResponse(ProvisionStatus status) {
        return provisionResponse(status, null, null);
    }

    private static ResponseEntity<String> provisionResponse(ProvisionStatus status,
                                                             String credentialsType,
                                                             String credentialsValue) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonConverter.toProvisionJson(
                        status.name(),
                        credentialsType,
                        credentialsValue,
                        status.errorMsg()));
    }

    private void publishDeviceEvent(String action, UUID tenantId, UUID deviceId, AuditStatus status,
                                    String failureMessage) {
        AuditTarget target = deviceId == null
                ? new AuditTarget(EntityType.DEVICE, null, null, tenantId, null)
                : AuditTarget.device(deviceId, tenantId);
        AuditEvent event = status == AuditStatus.SUCCEEDED
                ? AuditEvent.success(action, SecurityUsers.anonymous(), target, null, null)
                : AuditEvent.failure(action, SecurityUsers.anonymous(), target, failureMessage);
        eventPublisher.publishEvent(event);
    }

    private enum ProvisionStatus {
        SUCCESS(null),
        NOT_FOUND("Provision data was not found!"),
        FAILURE("Failed to provision device!");

        private final String errorMsg;

        ProvisionStatus(String errorMsg) {
            this.errorMsg = errorMsg;
        }

        String errorMsg() {
            return errorMsg;
        }
    }
}
