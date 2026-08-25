package com.roseboard.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "roseboard.security.oauth2")
public class OAuth2Properties {
    private String loginProcessingUrl;
    private Map<String, String> githubMapper;

    public String getLoginProcessingUrl() {
        return loginProcessingUrl;
    }

    public void setLoginProcessingUrl(String loginProcessingUrl) {
        this.loginProcessingUrl = loginProcessingUrl;
    }

    public Map<String, String> getGithubMapper() {
        return githubMapper;
    }

    public void setGithubMapper(Map<String, String> githubMapper) {
        this.githubMapper = githubMapper;
    }
}
