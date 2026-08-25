package com.roseboard.apikey;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

public class ApiKeyRequest {
    private UUID userId;
    private Boolean enabled;
    private String description;

    public UUID getUserId() { return userId; }
    public void setUserId(JsonNode value) {
        if (value == null || value.isNull()) { userId = null; return; }
        String raw = value.isTextual() ? value.asText() : value.path("id").asText(null);
        userId = raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
