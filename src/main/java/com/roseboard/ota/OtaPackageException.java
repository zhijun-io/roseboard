package com.roseboard.ota;

public final class OtaPackageException extends RuntimeException {
    private final OtaPackageErrorCode errorCode;

    public OtaPackageException(OtaPackageErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OtaPackageErrorCode errorCode() {
        return errorCode;
    }
}
