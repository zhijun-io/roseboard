package com.roseboard.infrastructure.notification.channel;

import org.springframework.http.HttpHeaders;
import com.roseboard.infrastructure.notification.spi.SmsClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpSmsClient implements SmsClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String baseUrl;
    private final String apiKey;

    public HttpSmsClient(JsonNode settings) {
        this.baseUrl = settings.path("baseUrl").asText(null);
        this.apiKey = settings.path("apiKey").asText(null);
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("SMS provider is not configured");
        }
    }

    @Override
    public int sendSms(String numberTo, String message) {
        String body = "{\"to\":\"" + escape(numberTo) + "\",\"content\":\"" + escape(message) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + "/v1/messages"))
                .timeout(Duration.ofSeconds(3))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202) {
                throw new IllegalStateException("HTTP SMS provider returned HTTP " + response.statusCode());
            }
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fake SMS provider request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Fake SMS provider request failed", exception);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
