package com.roseboard.ota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class OtaArtifactStore {
    private final OtaPackageArtifactChunkMapper chunkMapper;

    public OtaArtifactStore(OtaPackageArtifactChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public StoredArtifact storeStreaming(UUID packageId, InputStream input, long maxBytes, String algorithm) {
        clear(packageId);
        try {
            String normalizedAlgorithm = requireSupportedAlgorithm(algorithm);
            MessageDigest digest = MessageDigest.getInstance(normalizedAlgorithm);
            DigestInputStream in = new DigestInputStream(input, digest);
            byte[] buffer = new byte[OtaPackageLimits.ARTIFACT_CHUNK_BYTES];
            long total = 0;
            int chunkIndex = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    clear(packageId);
                    throw new OtaPackageException(OtaPackageErrorCode.QUOTA_EXCEEDED, "Package exceeds size limit");
                }
                OtaPackageArtifactChunkEntity chunk = new OtaPackageArtifactChunkEntity();
                chunk.setPackageId(packageId);
                chunk.setChunkIndex(chunkIndex++);
                chunk.setData(Arrays.copyOf(buffer, read));
                chunkMapper.insert(chunk);
            }
            return new StoredArtifact(total, normalizedAlgorithm, HexFormat.of().formatHex(digest.digest()));
        } catch (OtaPackageException exception) {
            throw exception;
        } catch (Exception exception) {
            clear(packageId);
            throw new OtaPackageException(OtaPackageErrorCode.UPLOAD_FAILED,
                    "Artifact upload failed: " + exception.getMessage());
        }
    }

    public byte[] readAll(UUID packageId) {
        List<OtaPackageArtifactChunkEntity> chunks = chunks(packageId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (OtaPackageArtifactChunkEntity chunk : chunks) {
            out.writeBytes(chunk.getData());
        }
        return out.toByteArray();
    }

    public byte[] readRange(UUID packageId, long sizeBytes, long offset, long length) {
        if (sizeBytes <= 0 || offset < 0 || offset >= sizeBytes || length <= 0) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "Invalid artifact range");
        }
        long remaining = sizeBytes - offset;
        long endExclusive = length >= remaining ? sizeBytes : offset + length;
        long resultLength = endExclusive - offset;
        if (resultLength > Integer.MAX_VALUE) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST, "Artifact range is too large");
        }
        int firstChunk = Math.toIntExact(offset / OtaPackageLimits.ARTIFACT_CHUNK_BYTES);
        int lastChunk = Math.toIntExact((endExclusive - 1) / OtaPackageLimits.ARTIFACT_CHUNK_BYTES);
        List<OtaPackageArtifactChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<OtaPackageArtifactChunkEntity>()
                        .eq(OtaPackageArtifactChunkEntity::getPackageId, packageId)
                        .ge(OtaPackageArtifactChunkEntity::getChunkIndex, firstChunk)
                        .le(OtaPackageArtifactChunkEntity::getChunkIndex, lastChunk)
                        .orderByAsc(OtaPackageArtifactChunkEntity::getChunkIndex));
        if (chunks.size() != lastChunk - firstChunk + 1) {
            throw new OtaPackageException(OtaPackageErrorCode.NOT_DOWNLOADABLE, "Artifact not available");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.toIntExact(resultLength));
        for (int i = 0; i < chunks.size(); i++) {
            OtaPackageArtifactChunkEntity chunk = chunks.get(i);
            if (chunk.getChunkIndex() != firstChunk + i) {
                throw new OtaPackageException(OtaPackageErrorCode.NOT_DOWNLOADABLE, "Artifact not available");
            }
            long chunkStart = (long) chunk.getChunkIndex() * OtaPackageLimits.ARTIFACT_CHUNK_BYTES;
            long from = Math.max(offset, chunkStart) - chunkStart;
            long to = Math.min(endExclusive, chunkStart + chunk.getData().length) - chunkStart;
            if (from < 0 || to <= from || to > chunk.getData().length) {
                throw new OtaPackageException(OtaPackageErrorCode.NOT_DOWNLOADABLE, "Artifact not available");
            }
            out.write(chunk.getData(), Math.toIntExact(from), Math.toIntExact(to - from));
        }
        return out.toByteArray();
    }

    public void clear(UUID packageId) {
        chunkMapper.delete(new LambdaQueryWrapper<OtaPackageArtifactChunkEntity>()
                .eq(OtaPackageArtifactChunkEntity::getPackageId, packageId));
    }

    static String requireSupportedAlgorithm(String algorithm) {
        String normalized = algorithm == null ? "" : algorithm.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("SHA-256") && !normalized.equals("SHA-384") && !normalized.equals("SHA-512")) {
            throw new OtaPackageException(OtaPackageErrorCode.INVALID_REQUEST,
                    "Unsupported checksum algorithm: " + algorithm);
        }
        return normalized;
    }

    private List<OtaPackageArtifactChunkEntity> chunks(UUID packageId) {
        List<OtaPackageArtifactChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<OtaPackageArtifactChunkEntity>()
                        .eq(OtaPackageArtifactChunkEntity::getPackageId, packageId)
                        .orderByAsc(OtaPackageArtifactChunkEntity::getChunkIndex));
        if (chunks.isEmpty()) {
            throw new OtaPackageException(OtaPackageErrorCode.NOT_DOWNLOADABLE, "Artifact not available");
        }
        return chunks;
    }

    public record StoredArtifact(long sizeBytes, String checksumAlgorithm, String checksumValue) {
    }
}
