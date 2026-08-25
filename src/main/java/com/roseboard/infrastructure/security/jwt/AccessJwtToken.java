package com.roseboard.infrastructure.security.jwt;

import java.io.Serializable;

public record AccessJwtToken(String token) implements Serializable {
}
