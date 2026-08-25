package com.roseboard.infrastructure.security.oauth2;

public class OAuth2ResolutionException extends RuntimeException {
    private final int status;

    public OAuth2ResolutionException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
