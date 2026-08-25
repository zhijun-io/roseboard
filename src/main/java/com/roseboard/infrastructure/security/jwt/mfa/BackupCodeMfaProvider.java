package com.roseboard.infrastructure.security.jwt.mfa;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.util.UUID;

@Service
public class BackupCodeMfaProvider implements MfaProvider {
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public BackupCodeMfaProvider(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public MfaProviderType type() { return MfaProviderType.BACKUP_CODE; }

    @Override public JsonNode generate(UUID userId, String subject) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("providerType", type().name());
        ArrayNode codes = config.putArray("codes");
        for (int i = 0; i < 10; i++) codes.add(String.format("%08d", random.nextInt(100_000_000)));
        return config;
    }

    @Override public boolean verify(UUID userId, JsonNode config, String code) {
        if (config == null || !config.path("codes").isArray() || code == null) return false;
        ArrayNode remaining = objectMapper.createArrayNode();
        boolean matched = false;
        for (JsonNode value : config.path("codes")) {
            if (code.equals(value.asText())) matched = true;
            else remaining.add(value);
        }
        if (matched) ((ObjectNode) config).set("codes", remaining);
        return matched;
    }
}
