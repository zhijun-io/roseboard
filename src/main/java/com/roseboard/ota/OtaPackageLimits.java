package com.roseboard.ota;

public final class OtaPackageLimits {
    public static final int MAX_KIND_CHARS = 64;
    public static final int MAX_TITLE_CHARS = 255;
    public static final int MAX_VERSION_CHARS = 64;
    public static final int MAX_TAG_CHARS = 255;
    public static final int MAX_FILE_NAME_CHARS = 255;
    public static final int MAX_CONTENT_TYPE_CHARS = 255;
    public static final int MAX_URL_CHARS = 2048;
    public static final int ARTIFACT_CHUNK_BYTES = 256 * 1024;
    public static final long DEFAULT_MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_MAX_TENANT_BYTES = 512L * 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENT_UPLOADS = 4;

    private OtaPackageLimits() {
    }
}
