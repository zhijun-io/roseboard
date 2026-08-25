package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.SecurityUserService;
import com.roseboard.infrastructure.security.api.SecurityUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationProviderTest {

    @Mock
    JwtDecoder jwtDecoder;

    @Mock
    SecurityUserService securityUserService;

    @Test
    void accessTokenKeepsUserAuthority() {
        SecurityUser user = user("SYS_ADMIN");
        JwtAuthenticationProvider provider = provider(user, JwtTokenType.ACCESS.value());

        Authentication result = provider.authenticate(token());

        assertEquals(user.getAuthorities(), result.getAuthorities());
    }

    @Test
    void mfaConfigurationTokenDoesNotKeepUserAuthority() {
        JwtAuthenticationProvider provider = provider(user("SYS_ADMIN"),
                JwtTokenType.MFA_CONFIGURATION.value());

        Authentication result = provider.authenticate(token());

        assertEquals(1, result.getAuthorities().size());
        assertTrue(result.getAuthorities().stream().anyMatch(authority ->
                JwtTokenType.MFA_CONFIGURATION.value().equals(authority.getAuthority())));
    }

    @Test
    void mfaPreVerificationTokenDoesNotKeepUserAuthority() {
        JwtAuthenticationProvider provider = provider(user("SYS_ADMIN"),
                JwtTokenType.MFA_PRE_VERIFICATION.value());

        Authentication result = provider.authenticate(token());

        assertEquals(1, result.getAuthorities().size());
        assertTrue(result.getAuthorities().stream().anyMatch(authority ->
                JwtTokenType.MFA_PRE_VERIFICATION.value().equals(authority.getAuthority())));
    }

    @Test
    void unknownTokenTypeIsRejected() {
        JwtAuthenticationProvider provider = provider(user("SYS_ADMIN"), "unexpected");

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(token()));
    }

    private JwtAuthenticationProvider provider(SecurityUser user, String tokenType) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(user.getUsername())
                .claim("tokenType", tokenType)
                .build();
        when(jwtDecoder.decode("token")).thenReturn(jwt);
        if (JwtTokenType.ACCESS.value().equals(tokenType)
                || JwtTokenType.MFA_CONFIGURATION.value().equals(tokenType)
                || JwtTokenType.MFA_PRE_VERIFICATION.value().equals(tokenType)) {
            when(securityUserService.loadUserByUsername(user.getUsername())).thenReturn(user);
        }
        return new JwtAuthenticationProvider(jwtDecoder, securityUserService);
    }

    private JwtAuthenticationToken token() {
        return new JwtAuthenticationToken(new AccessJwtToken("token"));
    }

    private SecurityUser user(String authority) {
        return new SecurityUser(UUID.randomUUID(), UUID.randomUUID(), null,
                "user@example.com", "password", authority, true);
    }
}
