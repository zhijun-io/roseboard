package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.api.SecurityUser;
import com.roseboard.infrastructure.security.SecurityUserService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class JwtAuthenticationProvider implements AuthenticationProvider {
    private final JwtDecoder jwtDecoder;
    private final SecurityUserService securityUserService;

    public JwtAuthenticationProvider(JwtDecoder jwtDecoder, SecurityUserService securityUserService) {
        this.jwtDecoder = jwtDecoder;
        this.securityUserService = securityUserService;
    }

    public SecurityUser authenticateAccessToken(String accessToken) throws AuthenticationException {
        Authentication authentication = authenticate(new JwtAuthenticationToken(new AccessJwtToken(accessToken)));
        return (SecurityUser) authentication.getPrincipal();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        AccessJwtToken rawAccessToken = (AccessJwtToken) authentication.getCredentials();
        if (rawAccessToken == null || rawAccessToken.token() == null || rawAccessToken.token().isBlank()) {
            throw new BadCredentialsException("Token is invalid");
        }
        try {
            Jwt jwt = jwtDecoder.decode(rawAccessToken.token());
            String tokenType = jwt.getClaimAsString("tokenType");
            if (!JwtTokenType.ACCESS.value().equals(tokenType)
                    && !JwtTokenType.MFA_CONFIGURATION.value().equals(tokenType)
                    && !JwtTokenType.MFA_PRE_VERIFICATION.value().equals(tokenType)) {
                throw new BadCredentialsException("Token is invalid");
            }
            SecurityUser user = securityUserService.loadUserByUsername(jwt.getSubject());
            if (Boolean.TRUE.equals(jwt.getClaim("publicUser"))) {
                user = user.withPublicUser(true);
            }
            var authorities = new ArrayList<GrantedAuthority>();
            if (JwtTokenType.ACCESS.value().equals(tokenType)) {
                authorities.addAll(user.getAuthorities());
            } else {
                authorities.add(new SimpleGrantedAuthority(tokenType));
            }
            return new JwtAuthenticationToken(user, authorities);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadCredentialsException("Token is invalid", exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return JwtAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
