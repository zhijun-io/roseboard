package com.roseboard.notification.channel;

import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ConnectionMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.UUID;

@Service
public class ChannelConfigService {
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "apiKey", "refreshToken", "clientSecret");

    private final NotificationChannelConfigMapper mapper;
    private final ObjectMapper objectMapper;

    public ChannelConfigService(NotificationChannelConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public ChannelView getPlatform(ChannelKind kind) {
        return toView(requirePlatformRow(kind), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChannelView savePlatform(ChannelKind kind, ChannelUpdate update) {
        NotificationChannelConfigEntity entity = mapper.selectScope(null, kind.name());
        long now = System.currentTimeMillis();
        if (entity == null) {
            entity = new NotificationChannelConfigEntity();
            entity.setId(UUID.randomUUID());
            entity.setTenantId(null);
            entity.setChannelKind(kind.name());
            entity.setConnectionMode(ConnectionMode.CUSTOM.name());
            entity.setCreatedTime(now);
            entity.setConfig(objectMapper.createObjectNode());
            entity.setSecrets(objectMapper.createObjectNode());
        }
        if (update.enabled() != null) {
            entity.setEnabled(update.enabled());
        } else if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (update.config() != null && update.config().isObject()) {
            entity.setConfig(update.config().deepCopy());
        }
        if (update.secrets() != null && update.secrets().isObject()) {
            entity.setSecrets(mergeSecrets(entity.getSecrets(), update.secrets()));
        }
        entity.setUpdatedTime(now);
        persist(entity);
        return toView(entity, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChannelView savePlatformOAuthSettings(ObjectNode oauthSettings) {
        NotificationChannelConfigEntity entity = requirePlatformRow(ChannelKind.EMAIL);
        ObjectNode config = entity.getConfig() instanceof ObjectNode existing
                ? existing.deepCopy() : objectMapper.createObjectNode();
        ObjectNode publicOauthSettings = oauthSettings.deepCopy();
        publicOauthSettings.remove("refreshToken");
        publicOauthSettings.remove("clientSecret");
        config.set("oauth2", publicOauthSettings);
        entity.setConfig(config);
        if (oauthSettings.has("refreshToken")) {
            ObjectNode secrets = entity.getSecrets() instanceof ObjectNode secretNode
                    ? secretNode.deepCopy() : objectMapper.createObjectNode();
            secrets.put("refreshToken", oauthSettings.path("refreshToken").asText());
            entity.setSecrets(secrets);
            oauthSettings.remove("refreshToken");
        }
        if (oauthSettings.has("clientSecret")) {
            ObjectNode secrets = entity.getSecrets() instanceof ObjectNode secretNode
                    ? secretNode.deepCopy() : objectMapper.createObjectNode();
            secrets.put("clientSecret", oauthSettings.path("clientSecret").asText());
            entity.setSecrets(secrets);
            oauthSettings.remove("clientSecret");
        }
        entity.setUpdatedTime(System.currentTimeMillis());
        mapper.updateById(entity);
        return toView(entity, false);
    }

    public ObjectNode platformOAuthSettings() {
        NotificationChannelConfigEntity entity = requirePlatformRow(ChannelKind.EMAIL);
        JsonNode config = entity.getConfig();
        if (config != null && config.has("oauth2") && config.get("oauth2").isObject()) {
            ObjectNode oauth = ((ObjectNode) config.get("oauth2")).deepCopy();
            JsonNode secrets = entity.getSecrets();
            if (secrets != null && secrets.has("refreshToken")) {
                oauth.put("refreshToken", secrets.path("refreshToken").asText());
            }
            if (secrets != null && secrets.has("clientSecret")) {
                oauth.put("clientSecret", secrets.path("clientSecret").asText());
            }
            return oauth;
        }
        return objectMapper.createObjectNode();
    }

    public JsonNode smsSettingsPublic() {
        NotificationChannelConfigEntity entity = mapper.selectScope(null, ChannelKind.SMS.name());
        if (entity == null || entity.getConfig() == null) {
            return objectMapper.createObjectNode();
        }
        ObjectNode copy = entity.getConfig() instanceof ObjectNode configNode
                ? configNode.deepCopy() : objectMapper.createObjectNode();
        copy.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
        return copy;
    }

    @Transactional(rollbackFor = Exception.class)
    public JsonNode saveSmsSettings(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("SMS settings must be an object");
        }
        if (value.path("baseUrl").asText("").isBlank()) {
            throw new IllegalArgumentException("SMS baseUrl is required");
        }
        if (value.path("apiKey").asText("").isBlank()) {
            throw new IllegalArgumentException("SMS apiKey is required");
        }
        ObjectNode config = objectMapper.createObjectNode();
        config.put("baseUrl", value.path("baseUrl").asText());
        if (value.has("provider")) {
            config.put("provider", value.path("provider").asText());
        }
        ObjectNode secrets = objectMapper.createObjectNode();
        secrets.put("apiKey", value.path("apiKey").asText());
        savePlatform(ChannelKind.SMS, new ChannelUpdate(
                value.path("enabled").asBoolean(true), null, config, secrets));
        return smsSettingsPublic();
    }

    public boolean platformEmailConfigured() {
        NotificationChannelConfigEntity entity = mapper.selectScope(null, ChannelKind.EMAIL.name());
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            return false;
        }
        JsonNode config = entity.getConfig();
        return config != null
                && !config.path("smtpHost").asText("").isBlank()
                && config.path("smtpPort").asInt(0) > 0;
    }

    public ChannelView getTenantChannel(UUID tenantId, ChannelKind kind) {
        NotificationChannelConfigEntity entity = mapper.selectScope(tenantId, kind.name());
        if (entity == null) {
            return new ChannelView(kind, true, ConnectionMode.INHERIT,
                    objectMapper.createObjectNode(), objectMapper.createObjectNode());
        }
        return toView(entity, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChannelView saveTenantChannel(UUID tenantId, ChannelKind kind, ChannelUpdate update) {
        if (kind == ChannelKind.WEB) {
            throw new IllegalArgumentException("WEB channel does not support connection configuration");
        }
        NotificationChannelConfigEntity entity = requireTenantRow(tenantId, kind);
        if (update.enabled() != null) {
            entity.setEnabled(update.enabled());
        }
        ConnectionMode mode = update.connectionMode() == null
                ? ConnectionMode.valueOf(entity.getConnectionMode()) : update.connectionMode();
        if (mode == ConnectionMode.CUSTOM) {
            validateCustomConfig(kind, update.config());
        }
        entity.setConnectionMode(mode.name());
        if (update.config() != null && update.config().isObject()) {
            entity.setConfig(update.config().deepCopy());
        }
        if (update.secrets() != null && update.secrets().isObject()) {
            entity.setSecrets(mergeSecrets(entity.getSecrets(), update.secrets()));
        }
        entity.setUpdatedTime(System.currentTimeMillis());
        persist(entity);
        return toView(entity, true);
    }

    public record ChannelView(
            ChannelKind kind,
            boolean enabled,
            ConnectionMode connectionMode,
            JsonNode config,
            JsonNode secrets
    ) {
    }

    public record ChannelUpdate(
            Boolean enabled,
            ConnectionMode connectionMode,
            JsonNode config,
            JsonNode secrets
    ) {
        public static ChannelUpdate fromJson(JsonNode payload, boolean parseConnectionMode) {
            ConnectionMode mode = null;
            if (parseConnectionMode && payload.hasNonNull("connectionMode")) {
                mode = ConnectionMode.parse(payload.get("connectionMode").asText());
            }
            return new ChannelUpdate(
                    payload.path("enabled").isBoolean() ? payload.path("enabled").asBoolean() : null,
                    mode,
                    payload.get("config"),
                    payload.get("secrets"));
        }
    }

    private NotificationChannelConfigEntity requireTenantRow(UUID tenantId, ChannelKind kind) {
        NotificationChannelConfigEntity entity = mapper.selectScope(tenantId, kind.name());
        long now = System.currentTimeMillis();
        if (entity != null) {
            return entity;
        }
        entity = new NotificationChannelConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setChannelKind(kind.name());
        entity.setConnectionMode(ConnectionMode.INHERIT.name());
        entity.setEnabled(true);
        entity.setConfig(objectMapper.createObjectNode());
        entity.setSecrets(objectMapper.createObjectNode());
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        return entity;
    }

    private void persist(NotificationChannelConfigEntity entity) {
        if (mapper.selectById(entity.getId()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    private ObjectNode mergeSecrets(JsonNode existing, JsonNode update) {
        ObjectNode merged = existing instanceof ObjectNode node
                ? node.deepCopy() : objectMapper.createObjectNode();
        for (var entry : update.properties()) {
            if (!entry.getValue().isNull() && !entry.getValue().asText("").isBlank()) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private ChannelView toView(NotificationChannelConfigEntity entity, boolean hideInheritConfig) {
        ObjectNode config = entity.getConfig() instanceof ObjectNode node
                ? node.deepCopy() : objectMapper.createObjectNode();
        ObjectNode secrets = entity.getSecrets() instanceof ObjectNode node
                ? node.deepCopy() : objectMapper.createObjectNode();
        for (var entry : secrets.properties()) {
            if (SECRET_KEYS.contains(entry.getKey())) {
                secrets.remove(entry.getKey());
            }
        }
        if (hideInheritConfig && ConnectionMode.INHERIT.name().equals(entity.getConnectionMode())) {
            config = objectMapper.createObjectNode();
            secrets = objectMapper.createObjectNode();
        }
        if (config.has("oauth2") && config.get("oauth2").isObject()) {
            ObjectNode oauth = (ObjectNode) config.get("oauth2");
            oauth.remove("clientSecret");
            oauth.remove("refreshToken");
        }
        return new ChannelView(
                ChannelKind.valueOf(entity.getChannelKind()),
                Boolean.TRUE.equals(entity.getEnabled()),
                ConnectionMode.valueOf(entity.getConnectionMode()),
                config,
                secrets);
    }

    private void validateCustomConfig(ChannelKind kind, JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("config object is required for CUSTOM connection");
        }
        if (kind == ChannelKind.EMAIL) {
            if (config.path("smtpHost").asText("").isBlank()) {
                throw new IllegalArgumentException("smtpHost is required");
            }
            if (config.path("smtpPort").asInt(0) <= 0) {
                throw new IllegalArgumentException("smtpPort must be positive");
            }
            if (config.path("from").asText("").isBlank()) {
                throw new IllegalArgumentException("from is required");
            }
        }
    }

    private NotificationChannelConfigEntity requirePlatformRow(ChannelKind kind) {
        NotificationChannelConfigEntity entity = mapper.selectScope(null, kind.name());
        if (entity != null) {
            return entity;
        }
        entity = new NotificationChannelConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(null);
        entity.setChannelKind(kind.name());
        entity.setEnabled(kind != ChannelKind.SMS);
        entity.setConnectionMode(kind == ChannelKind.WEB
                ? ConnectionMode.INHERIT.name() : ConnectionMode.CUSTOM.name());
        entity.setConfig(objectMapper.createObjectNode());
        entity.setSecrets(objectMapper.createObjectNode());
        long now = System.currentTimeMillis();
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        mapper.insert(entity);
        return entity;
    }
}
