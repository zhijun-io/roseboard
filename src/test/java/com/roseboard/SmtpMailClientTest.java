package com.roseboard;

import com.roseboard.infrastructure.notification.channel.SmtpMailClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmtpMailClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appliesTbSmtpMailClientSmtpOauthAndProxyConfiguration() {
        SmtpMailClient sender = new SmtpMailClient("localhost", 1025);
        sender.updateMailConfiguration(objectMapper.readTree("""
                {
                  "smtpHost":"smtp.example.com",
                  "smtpPort":587,
                  "smtpProtocol":"smtp",
                  "username":"user@example.com",
                  "password":"secret",
                  "enableTls":true,
                  "timeout":9000,
                  "enableOauth2":true,
                  "clientId":"client",
                  "clientSecret":"client-secret",
                  "refreshToken":"refresh-token",
                  "tokenUri":"https://issuer.example.com/token",
                  "enableProxy":true,
                  "proxyHost":"proxy.example.com",
                  "proxyPort":8080,
                  "proxyUsername":"proxy-user",
                  "proxyPassword":"proxy-password"
                }
                """));

        assertEquals("smtp.example.com", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("user@example.com", sender.getUsername());
        assertEquals("true", sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
        assertEquals("9000", sender.getJavaMailProperties().getProperty("mail.smtp.timeout"));
        assertEquals("XOAUTH2", sender.getJavaMailProperties().getProperty("mail.smtp.auth.mechanisms"));
        assertEquals("proxy.example.com", sender.getJavaMailProperties().getProperty("mail.smtp.proxy.host"));
        assertEquals("8080", sender.getJavaMailProperties().getProperty("mail.smtp.proxy.port"));
    }
}
