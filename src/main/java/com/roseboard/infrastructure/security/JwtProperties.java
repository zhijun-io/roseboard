package com.roseboard.infrastructure.security;

/** JWT settings consumed by the framework; storage of these settings belongs to the host. */
public class JwtProperties {
    private String tokenSigningKey;
    private String accessTokenTtl = "PT15M";
    private String refreshTokenTtl = "P30D";
    private String issuer = "roseboard";

    public String getTokenSigningKey() { return tokenSigningKey; }
    public void setTokenSigningKey(String tokenSigningKey) { this.tokenSigningKey = tokenSigningKey; }
    public String getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(String accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
    public String getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(String refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
