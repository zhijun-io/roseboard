package com.roseboard.infrastructure.security.oauth2;

import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/** Optional host adapter invoked after an OAuth2 provider authenticates a user. */
public interface OAuth2LoginSuccessHandler extends AuthenticationSuccessHandler {
}
