package com.roseboard.infrastructure.security.jwt;

import com.roseboard.infrastructure.security.JwtProperties;
import com.roseboard.infrastructure.security.api.LoginResponse;
import com.roseboard.infrastructure.security.api.SecurityUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtTokenFactory {

    private static final String REFRESH_SESSION_PREFIX = "roseboard:auth:refresh:";
    private static final String USER_REFRESH_SET_PREFIX = "roseboard:auth:user-refresh:";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final StringRedisTemplate redisTemplate;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final String issuer;

    public JwtTokenFactory(
            JwtEncoder encoder,
            JwtDecoder decoder,
            StringRedisTemplate redisTemplate,
            JwtProperties jwtProperties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.redisTemplate = redisTemplate;
        this.accessTokenTtl = Duration.parse(jwtProperties.getAccessTokenTtl());
        this.refreshTokenTtl = Duration.parse(jwtProperties.getRefreshTokenTtl());
        this.issuer = jwtProperties.getIssuer();
    }

    public TokenPair issue(SecurityUser principal) {
        return issue(principal, principal.isPublicUser());
    }

    public TokenPair issue(SecurityUser principal, boolean publicUser) {
        Instant now = Instant.now();
        String accessToken = encode(principal, now, accessTokenTtl, JwtTokenType.ACCESS, publicUser);
        String refreshToken = encode(principal, now, refreshTokenTtl, JwtTokenType.REFRESH, publicUser);
        String tokenHash = sha256(refreshToken);
        redisTemplate.opsForValue().set(refreshSessionKey(tokenHash), principal.getUserId().toString(), refreshTokenTtl);
        redisTemplate.opsForSet().add(userRefreshSetKey(principal.getUserId()), tokenHash);
        redisTemplate.expire(userRefreshSetKey(principal.getUserId()), refreshTokenTtl);
        return new TokenPair(accessToken, refreshToken);
    }
    public LoginResponse login(SecurityUser principal) {
        return login(principal, principal.isPublicUser());
    }

    public LoginResponse login(SecurityUser principal, boolean publicUser) {
        TokenPair pair = issue(principal.withPublicUser(publicUser), publicUser);
        return new LoginResponse(pair.token(), pair.refreshToken());
    }
    public LoginResponse issuePreVerification(SecurityUser principal) {
        Instant now = Instant.now();
        return new LoginResponse(
                encodeWithTokenAuthority(principal, now, Duration.ofMinutes(5),
                        JwtTokenType.MFA_PRE_VERIFICATION),
                null);
    }
    public LoginResponse issueMfaConfiguration(SecurityUser principal) {
        Instant now = Instant.now();
        return new LoginResponse(
                encodeWithTokenAuthority(principal, now, Duration.ofMinutes(30),
                        JwtTokenType.MFA_CONFIGURATION),
                null);
    }

    public RoseboardUserToken refresh(String refreshToken) {
        Jwt jwt = decoder.decode(refreshToken);
        if (!JwtTokenType.REFRESH.value().equals(jwt.getClaimAsString("tokenType"))) {
            throw new IllegalArgumentException("Not a refresh token");
        }
        String tokenHash = sha256(refreshToken);
        String userId = redisTemplate.opsForValue().get(refreshSessionKey(tokenHash));
        if (userId == null || !userId.equals(jwt.getClaimAsString("userId"))) {
            throw new IllegalArgumentException("Refresh token has been revoked or expired");
        }
        if (!Boolean.TRUE.equals(redisTemplate.delete(refreshSessionKey(tokenHash)))) {
            throw new IllegalArgumentException("Refresh token has already been rotated");
        }
        redisTemplate.opsForSet().remove(userRefreshSetKey(UUID.fromString(userId)), tokenHash);
        return new RoseboardUserToken(UUID.fromString(userId), jwt.getClaimAsString("sub"), jwt.getClaimAsString("authority"));
    }

    public void revoke(String refreshToken) {
        Jwt jwt = decoder.decode(refreshToken);
        String tokenHash = sha256(refreshToken);
        redisTemplate.delete(refreshSessionKey(tokenHash));
        String userId = jwt.getClaimAsString("userId");
        if (userId != null) {
            redisTemplate.opsForSet().remove(userRefreshSetKey(UUID.fromString(userId)), tokenHash);
        }
    }

    public void revokeAll(UUID userId) {
        String setKey = userRefreshSetKey(userId);
        Set<String> tokenHashes = redisTemplate.opsForSet().members(setKey);
        if (tokenHashes != null && !tokenHashes.isEmpty()) {
            redisTemplate.delete(tokenHashes.stream().map(this::refreshSessionKey).toList());
        }
        redisTemplate.delete(setKey);
    }

    private String encode(SecurityUser principal, Instant issuedAt, Duration ttl,
                          JwtTokenType tokenType, boolean publicUser) {
        return encodeClaims(principal, issuedAt, ttl, tokenType, principal.getAuthorityName(),
                publicUser);
    }

    private String encodeWithTokenAuthority(SecurityUser principal, Instant issuedAt, Duration ttl,
                                            JwtTokenType tokenType) {
        return encodeClaims(principal, issuedAt, ttl, tokenType, tokenType.value(), false);
    }

    private String encodeClaims(SecurityUser principal, Instant issuedAt, Duration ttl,
                                JwtTokenType tokenType, String authority, boolean publicUser) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(principal.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("userId", principal.getUserId().toString())
                .claim("authority", authority)
                .claim("scopes", Set.of(authority))
                .claim("tokenType", tokenType.value());
        if (publicUser) {
            claims.claim("publicUser", true);
        }
        if (principal.getTenantId() != null) {
            claims.claim("tenantId", principal.getTenantId().toString());
        }
        if (principal.getCustomerId() != null) {
            claims.claim("customerId", principal.getCustomerId().toString());
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private String refreshSessionKey(String tokenHash) { return REFRESH_SESSION_PREFIX + tokenHash; }
    private String userRefreshSetKey(UUID userId) { return USER_REFRESH_SET_PREFIX + userId; }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record TokenPair(String token, String refreshToken) { }
    public record RoseboardUserToken(UUID userId, String username, String authority) { }
}
