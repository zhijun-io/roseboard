package com.roseboard.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.roseboard.infrastructure.security.jwt.JwtPropertiesProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@AutoConfiguration
public class JwtAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public JwtProperties jwtProperties(JwtPropertiesProvider jwtPropertiesProvider) {
        JwtProperties jwtProperties = jwtPropertiesProvider.get();
        if (jwtProperties.getTokenSigningKey() == null || jwtProperties.getTokenSigningKey().isBlank()) {
            throw new IllegalStateException("JWT settings are missing from admin_setting key 'jwt'");
        }
        return jwtProperties;
    }

    @Bean
    public SecretKey jwtSecretKey(JwtProperties jwtProperties) {
        byte[] secret = Base64.getDecoder().decode(jwtProperties.getTokenSigningKey());
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties jwtProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.getIssuer()));
        return decoder;
    }
}
