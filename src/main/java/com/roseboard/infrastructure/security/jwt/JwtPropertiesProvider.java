package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.JwtProperties;

/** Host seam for loading signing settings from a database or configuration service. */
public interface JwtPropertiesProvider {
    JwtProperties get();
}
