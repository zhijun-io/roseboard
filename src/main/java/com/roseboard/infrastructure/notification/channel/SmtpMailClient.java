package com.roseboard.infrastructure.notification.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;

public class SmtpMailClient extends JavaMailSenderImpl {
    private final String defaultHost;
    private final int defaultPort;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile JsonNode oauthConfig;
    private volatile long oauthExpiresAt;

    public SmtpMailClient(@Value("${spring.mail.host:localhost}") String defaultHost,
                      @Value("${spring.mail.port:1025}") int defaultPort) {
        this.defaultHost = defaultHost;
        this.defaultPort = defaultPort;
        updateMailConfiguration(null);
    }

    public synchronized void updateMailConfiguration(JsonNode config) {
        JsonNode value = config == null ? objectMapper.createObjectNode() : config;
        setHost(value.path("smtpHost").asText(defaultHost));
        setPort(value.path("smtpPort").asInt(defaultPort));
        setProtocol(value.path("smtpProtocol").asText("smtp"));
        setUsername(value.path("username").asText(""));
        setPassword(value.path("password").asText(""));

        Properties properties = getJavaMailProperties();
        properties.clear();
        String protocol = getProtocol();
        String prefix = "mail." + protocol + ".";
        boolean oauth2 = value.path("enableOauth2").asBoolean(false);
        properties.put(prefix + "auth", Boolean.toString(!getUsername().isBlank() || oauth2));
        properties.put(prefix + "starttls.enable",
                Boolean.toString(value.path("enableTls").asBoolean(false)));
        properties.put(prefix + "ssl.enable",
                Boolean.toString(value.path("enableSsl").asBoolean(false)));
        properties.put(prefix + "connectiontimeout",
                Long.toString(value.path("timeout").asLong(3000)));
        properties.put(prefix + "timeout",
                Long.toString(value.path("timeout").asLong(3000)));
        properties.put(prefix + "writetimeout",
                Long.toString(value.path("timeout").asLong(3000)));
        if (oauth2) {
            properties.put(prefix + "auth.mechanisms", "XOAUTH2");
            properties.put(prefix + "auth.login.disable", "true");
            properties.put(prefix + "auth.plain.disable", "true");
        }
        if (value.path("enableProxy").asBoolean(false)) {
            String proxyHost = value.path("proxyHost").asText("");
            int proxyPort = value.path("proxyPort").asInt(0);
            if (!proxyHost.isBlank() && proxyPort > 0) {
                properties.put(prefix + "proxy.host", proxyHost);
                properties.put(prefix + "proxy.port", Integer.toString(proxyPort));
                properties.put(prefix + "proxy.user", value.path("proxyUsername").asText(""));
                properties.put(prefix + "proxy.password", value.path("proxyPassword").asText(""));
            }
        }
        oauthConfig = oauth2 ? value.deepCopy() : null;
        oauthExpiresAt = 0;
    }

    @Override
    protected synchronized Transport connectTransport()
            throws MessagingException {
        refreshOauth2TokenIfNecessary();
        return super.connectTransport();
    }

    private void refreshOauth2TokenIfNecessary() throws MessagingException {
        JsonNode config = oauthConfig;
        if (config == null || System.currentTimeMillis() < oauthExpiresAt - 60_000) {
            return;
        }
        String refreshToken = config.path("refreshToken").asText("");
        String tokenUri = config.path("tokenUri").asText("");
        String clientId = config.path("clientId").asText("");
        String clientSecret = config.path("clientSecret").asText("");
        if (refreshToken.isBlank() || tokenUri.isBlank() || clientId.isBlank()) {
            throw new MessagingException("OAuth2 mail configuration is incomplete");
        }
        try {
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, java.nio.charset.StandardCharsets.UTF_8)
                    + "&client_id=" + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8);
            if (!clientSecret.isBlank()) {
                form += "&client_secret=" + java.net.URLEncoder.encode(clientSecret, java.nio.charset.StandardCharsets.UTF_8);
            }
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form))
                    .build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MessagingException("OAuth2 token refresh failed: HTTP "
                        + response.statusCode());
            }
            JsonNode token = objectMapper.readTree(response.body());
            String accessToken = token.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new MessagingException("OAuth2 token response has no access_token");
            }
            setPassword(accessToken);
            oauthExpiresAt = System.currentTimeMillis()
                    + token.path("expires_in").asLong(3600) * 1000;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MessagingException("OAuth2 token refresh interrupted", exception);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new MessagingException("OAuth2 token refresh failed", exception);
        }
    }
}
