package com.roseboard.setting;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JwtSetting {
    @JsonAlias("tokenIssuer")
    private String issuer;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String tokenSigningKey;
    @Min(0)
    private Integer tokenExpirationTime;
    @Min(0)
    private Integer refreshTokenExpTime;
    private String accessTokenTtl;
    private String refreshTokenTtl;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getTokenSigningKey() {
        return tokenSigningKey;
    }

    public void setTokenSigningKey(String tokenSigningKey) {
        this.tokenSigningKey = tokenSigningKey;
    }

    public Integer getTokenExpirationTime() {
        return tokenExpirationTime;
    }

    public void setTokenExpirationTime(Integer tokenExpirationTime) {
        this.tokenExpirationTime = tokenExpirationTime;
    }

    public Integer getRefreshTokenExpTime() {
        return refreshTokenExpTime;
    }

    public void setRefreshTokenExpTime(Integer refreshTokenExpTime) {
        this.refreshTokenExpTime = refreshTokenExpTime;
    }

    public String getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(String accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public String getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(String refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }
}
