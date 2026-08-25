package com.roseboard.infrastructure.security.api;

public record LoginResponse(String token, String refreshToken) {
}
