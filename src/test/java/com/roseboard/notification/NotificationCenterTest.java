package com.roseboard.notification;

import com.roseboard.infrastructure.notification.model.*;
import com.roseboard.infrastructure.notification.internal.DefaultNotificationCenter;
import com.roseboard.infrastructure.notification.spi.NotificationChannelConfigResolver;
import com.roseboard.infrastructure.notification.spi.NotificationChannelSender;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateRenderer;
import com.roseboard.infrastructure.notification.spi.NotificationTemplateSource;
import com.roseboard.notification.template.DbNotificationTemplateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationCenterTest {
    private NotificationTemplateSource templateSource;
    private NotificationTemplateRenderer templateRenderer;
    private NotificationChannelConfigResolver channelConfigSource;
    private RecordingNotificationChannelSender webSender;
    private RecordingNotificationChannelSender emailSender;
    private DefaultNotificationCenter center;

    @BeforeEach
    void setUp() {
        templateSource = mock(NotificationTemplateSource.class);
        templateRenderer = mock(NotificationTemplateRenderer.class);
        channelConfigSource = mock(NotificationChannelConfigResolver.class);
        webSender = new RecordingNotificationChannelSender(ChannelKind.WEB);
        emailSender = new RecordingNotificationChannelSender(ChannelKind.EMAIL);
        center = new DefaultNotificationCenter(
                templateSource, templateRenderer, channelConfigSource, List.of(webSender, emailSender));
    }

    @Test
    void notifyDeliversEnabledChannels() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TemplateDefinition template = template("usage.threshold", "USAGE_LIMIT");
        when(templateSource.find(tenantId, "usage.threshold")).thenReturn(Optional.of(template));
        RenderedTemplate rendered = new RenderedTemplate(new EnumMap<>(Map.of(
                ChannelKind.WEB, new RenderedChannelContent("Usage WARNING", "metric is WARNING", false),
                ChannelKind.EMAIL, new RenderedChannelContent("Usage WARNING", "metric is WARNING", false))));
        when(templateRenderer.render(template, Map.of("status", "WARNING"), Set.of(ChannelKind.WEB, ChannelKind.EMAIL)))
                .thenReturn(rendered);
        when(channelConfigSource.resolve(tenantId, ChannelKind.WEB))
                .thenReturn(Optional.of(enabledConfig(ChannelKind.WEB)));
        when(channelConfigSource.resolve(tenantId, ChannelKind.EMAIL))
                .thenReturn(Optional.of(enabledConfig(ChannelKind.EMAIL)));

        NotifyResult result = center.notify(tenantId, new NotifyCommand(
                Set.of(ChannelKind.WEB, ChannelKind.EMAIL),
                List.of(new RecipientRef(userId, "admin@example.com", null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of("status", "WARNING"),
                NotifyOptions.systemNotification()));

        assertEquals(DeliveryStatus.SUCCESS, result.byChannel().get(ChannelKind.WEB).status());
        assertEquals(DeliveryStatus.SUCCESS, result.byChannel().get(ChannelKind.EMAIL).status());
        assertEquals(1, webSender.requests.size());
        assertEquals(1, emailSender.requests.size());
    }

    @Test
    void missingTemplateFailsFast() {
        UUID tenantId = UUID.randomUUID();
        when(templateSource.find(tenantId, "missing.key")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> center.notify(tenantId, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(UUID.randomUUID(), null, null)),
                "TEST",
                "missing.key",
                Map.of(),
                NotifyOptions.defaults())));
        verify(templateRenderer, never()).render(any(), any(), any());
    }

    @Test
    void authTemplatesIgnoreTenantOverrides() {
        assertTrue(DbNotificationTemplateSource.isSystemKey("auth.activation"));
        assertTrue(DbNotificationTemplateSource.isSystemKey("auth.mfa-email"));
    }

    @Test
    void customNotificationChannelSenderIsSelected() {
        UUID tenantId = UUID.randomUUID();
        RecordingNotificationChannelSender custom = new RecordingNotificationChannelSender(ChannelKind.WEB);
        DefaultNotificationCenter customCenter = new DefaultNotificationCenter(
                templateSource, templateRenderer, channelConfigSource, List.of(custom));
        TemplateDefinition template = template("usage.threshold", "USAGE_LIMIT");
        when(templateSource.find(tenantId, "usage.threshold")).thenReturn(Optional.of(template));
        when(templateRenderer.render(template, Map.of(), Set.of(ChannelKind.WEB)))
                .thenReturn(new RenderedTemplate(Map.of(
                        ChannelKind.WEB, new RenderedChannelContent("s", "b", false))));
        when(channelConfigSource.resolve(tenantId, ChannelKind.WEB))
                .thenReturn(Optional.of(enabledConfig(ChannelKind.WEB)));

        customCenter.notify(tenantId, new NotifyCommand(
                Set.of(ChannelKind.WEB),
                List.of(new RecipientRef(UUID.randomUUID(), null, null)),
                "USAGE_WARNING",
                "usage.threshold",
                Map.of(),
                NotifyOptions.systemNotification()));

        assertEquals(1, custom.requests.size());
    }

    private static TemplateDefinition template(String key, String type) {
        ObjectNode deliveryMethods = new ObjectMapper().createObjectNode();
        return new TemplateDefinition(UUID.randomUUID(), null, key, "SIMPLE", type, deliveryMethods);
    }

    private static ResolvedChannelConfig enabledConfig(ChannelKind kind) {
        if (kind == ChannelKind.EMAIL) {
            return new ResolvedChannelConfig(kind, ConfigScope.PLATFORM, ConnectionMode.INHERIT, true,
                    Map.of("smtpHost", "localhost", "smtpPort", 1025), Map.of());
        }
        return new ResolvedChannelConfig(kind, ConfigScope.PLATFORM, ConnectionMode.INHERIT, true, Map.of(), Map.of());
    }

    private static final class RecordingNotificationChannelSender implements NotificationChannelSender {
        private final ChannelKind kind;
        private final java.util.List<ChannelSendRequest> requests = new java.util.ArrayList<>();

        private RecordingNotificationChannelSender(ChannelKind kind) {
            this.kind = kind;
        }

        @Override
        public ChannelKind kind() {
            return kind;
        }

        @Override
        public DeliveryOutcome send(ChannelSendRequest request) {
            requests.add(request);
            return DeliveryOutcome.success();
        }

        @Override
        public ConnectivityResult verifyConnectivity(ResolvedChannelConfig config) {
            return new ConnectivityResult(kind, ConfigScope.PLATFORM, ConnectivityStatus.OK, null);
        }
    }
}
