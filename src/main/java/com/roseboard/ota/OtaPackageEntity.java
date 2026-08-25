package com.roseboard.ota;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.util.UUID;

@TableName("ota_package")
public class OtaPackageEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private Long createdTime;
    private UUID tenantId;
    private UUID deviceProfileId;
    private String kind;
    private String title;
    private String version;
    private String tag;
    private String fileName;
    private String contentType;
    private String artifactSource;
    private String externalUrl;
    private Long sizeBytes;
    private String checksumAlgorithm;
    private String checksumValue;
    private String signatureAlgorithm;
    private String signatureValue;
    private String signatureSigner;
    private String status;
    @Version
    private Long recordVersion;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceProfileId() { return deviceProfileId; }
    public void setDeviceProfileId(UUID deviceProfileId) { this.deviceProfileId = deviceProfileId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getArtifactSource() { return artifactSource; }
    public void setArtifactSource(String artifactSource) { this.artifactSource = artifactSource; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }
    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String checksumValue) { this.checksumValue = checksumValue; }
    public String getSignatureAlgorithm() { return signatureAlgorithm; }
    public void setSignatureAlgorithm(String signatureAlgorithm) { this.signatureAlgorithm = signatureAlgorithm; }
    public String getSignatureValue() { return signatureValue; }
    public void setSignatureValue(String signatureValue) { this.signatureValue = signatureValue; }
    public String getSignatureSigner() { return signatureSigner; }
    public void setSignatureSigner(String signatureSigner) { this.signatureSigner = signatureSigner; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRecordVersion() { return recordVersion; }
    public void setRecordVersion(Long recordVersion) { this.recordVersion = recordVersion; }
}
