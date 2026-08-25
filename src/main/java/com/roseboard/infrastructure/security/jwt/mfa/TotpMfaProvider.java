package com.roseboard.infrastructure.security.jwt.mfa;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class TotpMfaProvider implements MfaProvider {
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public TotpMfaProvider(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public MfaProviderType type() { return MfaProviderType.TOTP; }

    @Override public JsonNode generate(UUID userId, String subject) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("providerType", type().name());
        config.put("useByDefault", false);
        config.put("secret", randomSecret());
        return config;
    }

    @Override public boolean verify(UUID userId, JsonNode config, String code) {
        if (config == null) return false;
        return verifyCode(config.path("secret").asText(), code);
    }

    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long counter = System.currentTimeMillis() / 30_000L;
        for (long offset = -1; offset <= 1; offset++) {
            if (totp(secret, counter + offset).equals(code)) return true;
        }
        return false;
    }

    private String randomSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String totp(String secret, long counter) {
        try {
            byte[] key = Base64.getUrlDecoder().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int index = hash[hash.length - 1] & 0xf;
            int value = ((hash[index] & 0x7f) << 24 | (hash[index + 1] & 0xff) << 16
                    | (hash[index + 2] & 0xff) << 8 | (hash[index + 3] & 0xff)) % 1_000_000;
            return String.format("%06d", value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid TOTP secret", exception);
        }
    }
}
