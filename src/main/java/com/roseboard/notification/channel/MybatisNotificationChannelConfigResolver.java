package com.roseboard.notification.channel;

import com.roseboard.infrastructure.notification.spi.NotificationChannelConfigResolver;
import com.roseboard.infrastructure.notification.model.ChannelKind;
import com.roseboard.infrastructure.notification.model.ConfigScope;
import com.roseboard.infrastructure.notification.model.ConnectionMode;
import com.roseboard.infrastructure.notification.model.ResolvedChannelConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class MybatisNotificationChannelConfigResolver implements NotificationChannelConfigResolver {
    private final NotificationChannelConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final String defaultSmtpHost;
    private final int defaultSmtpPort;

    public MybatisNotificationChannelConfigResolver(NotificationChannelConfigMapper mapper, ObjectMapper objectMapper,
                                                    @Value("${spring.mail.host:}") String defaultSmtpHost,
                                                    @Value("${spring.mail.port:0}") int defaultSmtpPort) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.defaultSmtpHost = defaultSmtpHost;
        this.defaultSmtpPort = defaultSmtpPort;
    }

    @Override
    public Optional<ResolvedChannelConfig> resolve(UUID tenantId, ChannelKind kind) {
        if (kind == ChannelKind.WEB) {
            return Optional.of(resolveWeb(tenantId));
        }
        ResolvedChannelConfig platform = resolvePlatform(kind)
                .orElseGet(() -> fallbackPlatform(kind));
        if (tenantId == null) {
            return Optional.of(platform);
        }
        NotificationChannelConfigEntity tenantRow = mapper.selectScope(tenantId, kind.name());
        if (tenantRow == null) {
            return Optional.of(inherit(platform, ConfigScope.TENANT, true));
        }
        if (ConnectionMode.INHERIT.name().equals(tenantRow.getConnectionMode())) {
            boolean enabled = Boolean.TRUE.equals(tenantRow.getEnabled()) && platform.enabled();
            return Optional.of(inherit(platform, ConfigScope.TENANT, enabled));
        }
        return Optional.of(toResolved(tenantRow, ConfigScope.TENANT));
    }

    private ResolvedChannelConfig resolveWeb(UUID tenantId) {
        if (tenantId != null) {
            NotificationChannelConfigEntity tenantRow = mapper.selectScope(tenantId, ChannelKind.WEB.name());
            if (tenantRow != null && ConnectionMode.INHERIT.name().equals(tenantRow.getConnectionMode())) {
                ResolvedChannelConfig platform = resolvePlatform(ChannelKind.WEB)
                        .orElse(defaultWebPlatform());
                boolean enabled = Boolean.TRUE.equals(tenantRow.getEnabled()) && platform.enabled();
                return inherit(platform, ConfigScope.TENANT, enabled);
            }
            if (tenantRow != null) {
                return toResolved(tenantRow, ConfigScope.TENANT);
            }
        }
        return resolvePlatform(ChannelKind.WEB).orElse(defaultWebPlatform());
    }

    private Optional<ResolvedChannelConfig> resolvePlatform(ChannelKind kind) {
        NotificationChannelConfigEntity row = mapper.selectScope(null, kind.name());
        return row == null ? Optional.empty() : Optional.of(toResolved(row, ConfigScope.PLATFORM));
    }

    private ResolvedChannelConfig fallbackPlatform(ChannelKind kind) {
        return switch (kind) {
            case WEB -> defaultWebPlatform();
            case EMAIL -> fallbackEmailPlatform();
            case SMS -> new ResolvedChannelConfig(
                    ChannelKind.SMS, ConfigScope.PLATFORM, ConnectionMode.INHERIT, false, Map.of(), Map.of());
        };
    }

    private ResolvedChannelConfig fallbackEmailPlatform() {
        if (defaultSmtpHost == null || defaultSmtpHost.isBlank() || defaultSmtpPort <= 0) {
            return new ResolvedChannelConfig(
                    ChannelKind.EMAIL, ConfigScope.PLATFORM, ConnectionMode.INHERIT, false, Map.of(), Map.of());
        }
        return new ResolvedChannelConfig(
                ChannelKind.EMAIL, ConfigScope.PLATFORM, ConnectionMode.CUSTOM, true,
                Map.of("smtpHost", defaultSmtpHost, "smtpPort", defaultSmtpPort, "tls", true),
                Map.of());
    }

    private static ResolvedChannelConfig defaultWebPlatform() {
        return new ResolvedChannelConfig(
                ChannelKind.WEB, ConfigScope.PLATFORM, ConnectionMode.INHERIT, true, Map.of(), Map.of());
    }

    private static ResolvedChannelConfig inherit(ResolvedChannelConfig platform, ConfigScope scope, boolean enabled) {
        return new ResolvedChannelConfig(
                platform.kind(), scope, ConnectionMode.INHERIT, enabled, platform.config(), platform.secrets());
    }

    private ResolvedChannelConfig toResolved(NotificationChannelConfigEntity row, ConfigScope scope) {
        return new ResolvedChannelConfig(
                ChannelKind.valueOf(row.getChannelKind()),
                scope,
                ConnectionMode.valueOf(row.getConnectionMode()),
                Boolean.TRUE.equals(row.getEnabled()),
                jsonToMap(row.getConfig()),
                jsonToMap(row.getSecrets()));
    }

    private Map<String, Object> jsonToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }
}
