package com.roseboard.ota;

import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

@TableName("ota_package_artifact_chunk")
public class OtaPackageArtifactChunkEntity {
    private UUID packageId;
    private Integer chunkIndex;
    private byte[] data;

    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
