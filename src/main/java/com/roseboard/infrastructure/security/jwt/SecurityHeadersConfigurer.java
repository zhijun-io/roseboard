package com.roseboard.infrastructure.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "roseboard.security.headers")
public class SecurityHeadersConfigurer {
    private boolean contentTypeOptions = true;
    private boolean frameOptions = true;
    private String frameOptionsValue = "SAMEORIGIN";
    private String referrerPolicy = "strict-origin-when-cross-origin";
    private String contentSecurityPolicy;

    public void customize(HeadersConfigurer<?> headers) {
        if (contentTypeOptions) {
            headers.contentTypeOptions(config -> {
            });
        }
        if (frameOptions) {
            if ("DENY".equalsIgnoreCase(frameOptionsValue)) {
                headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
            } else {
                headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin);
            }
        }
        if (referrerPolicy != null && !referrerPolicy.isBlank()) {
            headers.addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", referrerPolicy));
        }
        if (contentSecurityPolicy != null && !contentSecurityPolicy.isBlank()) {
            headers.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy));
        }
    }

    public boolean isContentTypeOptions() {
        return contentTypeOptions;
    }

    public void setContentTypeOptions(boolean contentTypeOptions) {
        this.contentTypeOptions = contentTypeOptions;
    }

    public boolean isFrameOptions() {
        return frameOptions;
    }

    public void setFrameOptions(boolean frameOptions) {
        this.frameOptions = frameOptions;
    }

    public String getFrameOptionsValue() {
        return frameOptionsValue;
    }

    public void setFrameOptionsValue(String frameOptionsValue) {
        this.frameOptionsValue = frameOptionsValue;
    }

    public String getReferrerPolicy() {
        return referrerPolicy;
    }

    public void setReferrerPolicy(String referrerPolicy) {
        this.referrerPolicy = referrerPolicy;
    }

    public String getContentSecurityPolicy() {
        return contentSecurityPolicy;
    }

    public void setContentSecurityPolicy(String contentSecurityPolicy) {
        this.contentSecurityPolicy = contentSecurityPolicy;
    }
}
