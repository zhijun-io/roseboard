package com.roseboard;

import com.roseboard.infrastructure.notification.channel.HttpSmsClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSmsClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSmsToFakeProvider() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        ObjectMapper objectMapper = new ObjectMapper();
        HttpSmsClient sender = new HttpSmsClient(objectMapper.readTree(
                "{\"baseUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\",\"apiKey\":\"test-only-key\"}"));
        sender.sendSms("+8613800138000", "您的验证码是 123456，5 分钟内有效");

        assertEquals("test-only-key", apiKey.get());
        assertTrue(body.get().contains("+8613800138000"));
        assertTrue(body.get().contains("123456"));
    }
}
