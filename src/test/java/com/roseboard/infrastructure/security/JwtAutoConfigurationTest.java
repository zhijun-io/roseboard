package com.roseboard.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAutoConfigurationTest {

    @Test
    void decoderRejectsTokenWithDifferentIssuer() {
        byte[] secret = "a-secret-that-is-long-enough-123456".getBytes(StandardCharsets.UTF_8);
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("roseboard");
        properties.setTokenSigningKey(Base64.getEncoder().encodeToString(secret));

        JwtAutoConfiguration configuration = new JwtAutoConfiguration();
        var key = configuration.jwtSecretKey(properties);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        String token = encoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("attacker")
                        .subject("user@example.com")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build())).getTokenValue();

        assertThrows(Exception.class, () -> configuration.jwtDecoder(key, properties).decode(token));
    }
}
